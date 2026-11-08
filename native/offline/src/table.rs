//! A one-table hanab.live "server" for playing against the bot offline.
//!
//! The human sits at seat 0 and everyone else is a bot (`hanab-bot-rs`). The table speaks the hanab.live
//! messages the app already understands (`tableStart`, `init`, `gameActionList`, `gameAction`, ...), so
//! the game screen does not know it is offline. Rules and bot play come from the bot library, driven the
//! way its self-play does: every seat keeps its own `Game`, from its own point of view.

use std::cell::RefCell;
use std::collections::VecDeque;
use std::panic::{catch_unwind, AssertUnwindSafe};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::mpsc::Receiver;
use std::sync::{Arc, Condvar, Mutex};
use std::time::{Duration, Instant, SystemTime, UNIX_EPOCH};

use hanab_bot::basics::action::{Action, DrawAction, TurnAction};
use hanab_bot::basics::clue::ClueKind;
use hanab_bot::basics::state::TableOptions;
use hanab_bot::basics::{Convention, Game, Identity, PerformAction, State, Variant, HAND_SIZE};
use hanab_bot::settings::ConventionKind;
use hanab_bot::tools::self_play::{full_deck, Rng};

use crate::seed;
use serde_json::{json, Value};

const TABLE_ID: usize = 1;

/// What the app asked for.
pub struct Config {
    pub variant: String,
    /// Everyone at the table, the human included (2 to 5).
    pub players: usize,
    pub convention: String,
    /// The part of the seed the player picks (`50` in `p3v0s50`); the table builds the rest. Any text.
    pub seed_suffix: String,
    pub human_name: String,
    /// Pause after each bot move, so the app can animate it.
    pub pace_ms: u64,
}

pub enum Event {
    /// A command the app sent, e.g. `action`.
    Human(String, Value),
    Close,
}

/// Messages for the app, oldest first.
pub struct Outbox {
    queue: Mutex<VecDeque<String>>,
    ready: Condvar,
    pub closed: AtomicBool,
}

impl Outbox {
    pub fn new() -> Self {
        Self { queue: Mutex::new(VecDeque::new()), ready: Condvar::new(), closed: AtomicBool::new(false) }
    }

    fn push(&self, command: &str, data: Value) {
        self.queue.lock().unwrap().push_back(format!("{command} {data}"));
        self.ready.notify_all();
    }

    /// The next message, waiting up to `timeout`. `None` if there was none.
    pub fn pop(&self, timeout: Duration) -> Option<String> {
        let deadline = Instant::now() + timeout;
        let mut q = self.queue.lock().unwrap();
        // A wait can end early without a message (a spurious wake-up); only the deadline or a close ends it for good.
        while q.is_empty() && !self.closed.load(Ordering::SeqCst) {
            let Some(left) = deadline.checked_duration_since(Instant::now()) else { break };
            q = self.ready.wait_timeout(q, left).unwrap().0;
        }
        q.pop_front()
    }

    pub fn close(&self) {
        self.closed.store(true, Ordering::SeqCst);
        self.ready.notify_all();
    }
}

fn identity_json(id: Option<Identity>) -> (i64, i64) {
    id.map_or((-1, -1), |id| (id.suit_index as i64, id.rank as i64))
}

fn status(game: &Game) -> Value {
    let s = &game.state;
    // In a Clue Starved game the server counts clues at twice their value (a clue costs 2, a discard gives 1, up to
    // 16), which is how the app reads them too.
    let clues = if s.variant.clue_starved { s.clue_tokens * 2 + usize::from(s.half_clue_token) } else { s.clue_tokens };
    json!({"type": "status", "clues": clues, "score": s.score(), "maxScore": s.max_score()})
}

struct Table<'a> {
    cfg: &'a Config,
    /// Where the human sits; chosen at random for each game, so sometimes a bot starts.
    human: usize,
    /// The seed the way the site names it, e.g. `p3v0s50`; it decides the deal.
    seed: String,
    out: &'a Outbox,
    rx: &'a Receiver<Event>,
    variant: Arc<Variant>,
    deck: Vec<Identity>,
    games: Vec<Game>,
    names: Vec<String>,
    /// Every action of the game with all cards shown, as a replay needs them (see `offlineRecord`).
    record: RefCell<Vec<Value>>,
    /// Milliseconds each seat has used: you while the table waited for you, a bot while it was working out its move.
    times: RefCell<Vec<u64>>,
    started: Instant,
    started_at_ms: u64,
}

/// How a game ended, as far as what comes next goes.
enum End {
    /// The app went away.
    Closed,
    /// The game finished; the app may ask for a rematch.
    Over,
}

pub fn run(cfg: Config, rx: Receiver<Event>, out: Arc<Outbox>) {
    let (variant, conv) = match (Variant::get(&cfg.variant), convention(&cfg.convention)) {
        (Ok(v), Ok(c)) => (v, c),
        (Err(e), _) | (_, Err(e)) => {
            out.push("warning", json!({"warning": e}));
            out.close();
            return;
        }
    };
    let mut suffix = cfg.seed_suffix.clone();
    loop {
        let mut table = Table::new(&cfg, &suffix, &variant, &conv, &rx, &out);
        let end = catch_unwind(AssertUnwindSafe(|| table.play()));
        match end {
            // A rematch is the next deal of the series, as on the site.
            Ok(End::Over) if table.wait_for_rematch() => suffix = seed::next_suffix(&suffix),
            Ok(_) => break,
            Err(_) => {
                out.push("warning", json!({"warning": "The offline game stopped because of an internal error."}));
                break;
            }
        }
    }
    out.close();
}

impl<'a> Table<'a> {
    fn new(
        cfg: &'a Config,
        suffix: &str,
        variant: &Arc<Variant>,
        conv: &Arc<dyn Convention>,
        rx: &'a Receiver<Event>,
        out: &'a Outbox,
    ) -> Self {
        let num = cfg.players;
        // The same seed deals the same cards, seats you in the same place and names the bots the same.
        let seed = seed::seed_name(num, variant.id, suffix);
        let numeric = seed::numeric(&seed);
        // You made the table and the bots joined after you; the seed then shuffles the seating, as on the site.
        let joined: Vec<String> =
            std::iter::once(cfg.human_name.clone()).chain(bot_names(numeric, num - 1, &cfg.human_name)).collect();
        let mut deck = full_deck(variant);
        let seats = seed::deal(&mut deck, num, &seed);
        let human = seats.iter().position(|&who| who == 0).unwrap_or(0);
        let names: Vec<String> = seats.iter().map(|&who| joined[who].clone()).collect();

        let options = TableOptions::new(num, &variant.name);
        let games: Vec<Game> = (0..num)
            .map(|i| {
                let state = State::new(names.clone(), i, Arc::clone(variant), options.clone());
                let mut game = Game::new(0, state, false, Arc::clone(conv));
                game.catchup = true;
                for player_index in 0..num {
                    for _ in 0..HAND_SIZE[num] {
                        let order = game.state.next_card_order;
                        let draw = DrawAction { player_index, order, id: (player_index != i).then(|| deck[order]) };
                        game.handle_action(&Action::Draw(draw)).expect("failed to deal");
                    }
                }
                game
            })
            .collect();
        Table {
            cfg,
            seed,
            human,
            out,
            rx,
            variant: Arc::clone(variant),
            deck,
            games,
            names,
            record: RefCell::new(Vec::new()),
            times: RefCell::new(vec![0; num]),
            started: Instant::now(),
            started_at_ms: 0,
        }
    }

    /// After a finished game: true if the app wants to play again.
    fn wait_for_rematch(&self) -> bool {
        loop {
            match self.next_event() {
                Some((c, _)) if c == "tableRestart" => return true,
                Some((c, _)) if c == "tableUnattend" || c == "tableLeave" => return false,
                Some(_) => continue,
                None => return false,
            }
        }
    }
}

impl<'a> Table<'a> {
    /// Waits for a command from the app. `None` if the app closed the table.
    fn next_event(&self) -> Option<(String, Value)> {
        match self.rx.recv() {
            Ok(Event::Human(command, data)) => Some((command, data)),
            Ok(Event::Close) | Err(_) => None,
        }
    }

    /// Ignores everything until the app sends `command`.
    fn wait_for(&self, command: &str) -> Option<()> {
        loop {
            let (c, _) = self.next_event()?;
            if c == command {
                return Some(());
            }
        }
    }

    fn deal_actions(&self) -> Vec<Value> {
        let n = self.cfg.players;
        let mut list = Vec::new();
        for p in 0..n {
            for k in 0..HAND_SIZE[n] {
                let order = p * HAND_SIZE[n] + k;
                let (suit, rank) = identity_json((p != self.human).then(|| self.deck[order]));
                list.push(json!({"type": "draw", "playerIndex": p, "order": order, "suitIndex": suit, "rank": rank}));
            }
        }
        list.push(status(&self.games[self.human]));
        list.push(json!({"type": "turn", "num": 0, "currentPlayerIndex": 0}));
        list
    }

    fn play(&mut self) -> End {
        // The same hand-shake the real server has with the app.
        self.out.push("tableStart", json!({"tableID": TABLE_ID}));
        if self.wait_for("getGameInfo1").is_none() {
            return End::Closed;
        }
        let opts = json!({
            "variantName": self.variant.name, "timed": false, "speedrun": false, "cardCycle": false,
            "deckPlays": false, "emptyClues": false, "oneExtraCard": false, "oneLessCard": false,
            "allOrNothing": false, "detrimentalCharacters": false,
        });
        self.out.push(
            "init",
            json!({
                "tableID": TABLE_ID, "playerNames": self.names, "ourPlayerIndex": self.human, "spectating": false,
                "replay": false, "sharedReplay": false, "seed": self.seed,
                "options": opts, "characterAssignments": [], "characterMetadata": [],
            }),
        );
        if self.wait_for("getGameInfo2").is_none() {
            return End::Closed;
        }
        let dealt = self.deal_actions();
        self.out.push("gameActionList", json!({"tableID": TABLE_ID, "list": dealt}));
        *self.record.borrow_mut() = dealt.iter().map(|a| self.reveal(a)).collect();
        if self.wait_for("loaded").is_none() {
            return End::Closed;
        }
        self.started = Instant::now();
        self.started_at_ms = SystemTime::now().duration_since(UNIX_EPOCH).map_or(0, |d| d.as_millis() as u64);

        loop {
            if self.games[0].state.ended() {
                return End::Over;
            }
            let current = self.games[0].state.current_player_index;
            let perform = if current == self.human {
                let waited = Instant::now();
                let chosen = self.human_move();
                self.times.borrow_mut()[current] += waited.elapsed().as_millis() as u64;
                match chosen {
                    Some(p) => p,
                    None => return End::Closed,
                }
            } else {
                // Thinking can take a while; the app only sees the result. Every bot waits the same
                // (the "thinking pause", short in fast mode), including the one right after the human.
                let started = Instant::now();
                let bot = &self.games[current];
                let chosen = catch_unwind(AssertUnwindSafe(|| bot.take_action()));
                self.times.borrow_mut()[current] += started.elapsed().as_millis() as u64;
                let pause = Duration::from_millis(self.cfg.pace_ms);
                if let Some(rest) = pause.checked_sub(started.elapsed()) {
                    std::thread::sleep(rest);
                }
                match chosen {
                    Ok(p) => p,
                    Err(_) => {
                        self.finish(current, 4);
                        return End::Over;
                    }
                }
            };

            if let PerformAction::Terminate { .. } = perform {
                self.finish(current, 4);
                return End::Over;
            }
            if self.apply(current, perform).is_err() {
                self.finish(current, 4);
                return End::Over;
            }
            if self.games[0].state.ended() {
                return End::Over;
            }
        }
    }

    /// Waits for a legal move from the human; says why if one is not.
    fn human_move(&self) -> Option<PerformAction> {
        loop {
            let (command, data) = self.next_event()?;
            if command == "tableTerminate" {
                return Some(PerformAction::Terminate { target: self.human, value: 0 });
            }
            if command != "action" {
                continue;
            }
            let get = |k: &str| data[k].as_i64().unwrap_or(-1);
            match self.check(get("type"), get("target"), get("value")) {
                Ok(p) => return Some(p),
                Err(why) => self.out.push("warning", json!({"warning": why})),
            }
        }
    }

    fn check(&self, kind: i64, target: i64, value: i64) -> Result<PerformAction, String> {
        let s = &self.games[self.human].state;
        let (target, value) = (target.max(-1), value.max(-1));
        match kind {
            0 | 1 => {
                if target < 0 || !s.hands[self.human].contains(&(target as usize)) {
                    return Err("That card is not in your hand.".into());
                }
                // An Orange card is played by discarding it, so the server allows a discard at any clue count then.
                if kind == 1 && s.clue_tokens >= 8 && !self.variant.suits.iter().any(|x| x.suit_type.inverted) {
                    return Err("You cannot discard while the team has 8 clues.".into());
                }
                let t = target as usize;
                Ok(if kind == 0 { PerformAction::Play { target: t } } else { PerformAction::Discard { target: t } })
            }
            2 | 3 => {
                if s.clue_tokens == 0 {
                    return Err("There are no clues left.".into());
                }
                if target < 0 || target as usize >= s.num_players || target as usize == self.human {
                    return Err("Choose another player to clue.".into());
                }
                let (t, v) = (target as usize, value as usize);
                let perform = if kind == 2 {
                    if value < 0 || v >= self.variant.colourable_suits.len() {
                        return Err("That is not a colour in this game.".into());
                    }
                    PerformAction::Colour { target: t, value: v }
                } else {
                    if !(1..=5).contains(&value) {
                        return Err("A rank clue is a number from 1 to 5.".into());
                    }
                    PerformAction::Rank { target: t, value: v }
                };
                match perform.to_action(s, self.human, Some(&self.deck), false) {
                    Action::Clue(c) if c.list.is_empty() => Err("That clue would touch no cards.".into()),
                    _ => Ok(perform),
                }
            }
            4 => Ok(PerformAction::Terminate { target: self.human, value: 0 }),
            _ => Err("The game does not know that move.".into()),
        }
    }

    /// Applies one move to every seat and tells the app what happened, in the server's order.
    fn apply(&mut self, current: usize, perform: PerformAction) -> Result<(), String> {
        let n = self.cfg.players;
        // The bot library counts turns from 1, the server (and so the app) from 0.
        let turn_before = self.games[0].state.turn_count - 1;
        let strikes_before = self.games[0].state.strikes;
        let action = perform.to_action(&self.games[0].state, current, Some(&self.deck), false);

        let mut events: Vec<Value> = Vec::new();
        match &action {
            Action::Clue(c) => {
                let kind = if c.clue.kind == ClueKind::Colour { 0 } else { 1 };
                events.push(json!({
                    "type": "clue", "clue": {"type": kind, "value": c.clue.value}, "giver": c.giver,
                    "list": c.list, "target": c.target, "turn": turn_before,
                }));
            }
            Action::Play(p) => {
                let (suit, rank) = identity_json(p.id);
                events.push(json!({"type": "play", "playerIndex": p.player_index, "order": p.order, "suitIndex": suit, "rank": rank}));
            }
            Action::Discard(d) => {
                let (suit, rank) = identity_json(d.id);
                if d.failed {
                    events.push(json!({"type": "strike", "num": strikes_before + 1, "turn": turn_before, "order": d.order}));
                }
                events.push(json!({
                    "type": "discard", "playerIndex": d.player_index, "order": d.order, "suitIndex": suit,
                    "rank": rank, "failed": d.failed,
                }));
            }
            _ => {}
        }

        let deck = &self.deck;
        for game in &mut self.games {
            let a = perform.to_action(&game.state, current, Some(deck), false);
            game.handle_action(&a).map_err(|e| e.to_string())?;
            if game.state.ended() {
                continue;
            }
            if game.state.next_card_order < deck.len() && matches!(perform, PerformAction::Play { .. } | PerformAction::Discard { .. }) {
                let order = game.state.next_card_order;
                let viewer = game.state.our_player_index;
                let draw = DrawAction { player_index: current, order, id: (current != viewer).then(|| deck[order]) };
                game.handle_action(&Action::Draw(draw)).map_err(|e| e.to_string())?;
            }
            let next = game.state.next_player_index(current);
            let turn = game.state.turn_count;
            game.handle_action(&Action::Turn(TurnAction { num: turn, current_player_index: Some(next) }))
                .map_err(|e| e.to_string())?;
        }

        // The card drawn to replace a played or discarded one, as the human sees it.
        if matches!(perform, PerformAction::Play { .. } | PerformAction::Discard { .. }) && !self.games[0].state.ended() {
            let order = self.games[0].state.next_card_order - 1;
            if order < self.deck.len() && order >= n * HAND_SIZE[n] {
                let (suit, rank) = identity_json((current != self.human).then(|| self.deck[order]));
                events.push(json!({"type": "draw", "playerIndex": current, "order": order, "suitIndex": suit, "rank": rank}));
            }
        }
        events.push(status(&self.games[self.human]));

        for e in events {
            self.emit(e);
        }
        let state = &self.games[0].state;
        if state.ended() {
            let condition = if state.strikes == 3 { 2 } else { 1 };
            self.game_over(current, condition);
        } else {
            let turn = json!({"type": "turn", "num": state.turn_count - 1, "currentPlayerIndex": state.current_player_index});
            self.emit(turn);
        }
        Ok(())
    }

    /// Sends one game action to the app, and keeps it for the record.
    fn emit(&self, action: Value) {
        self.record.borrow_mut().push(self.reveal(&action));
        self.out.push("gameAction", json!({"tableID": TABLE_ID, "action": action}));
    }

    /// An action as a spectator sees it: a draw into the human hand shows the card.
    fn reveal(&self, action: &Value) -> Value {
        let mut a = action.clone();
        if a["type"] == "draw" && a["suitIndex"] == -1 {
            if let Some(id) = a["order"].as_u64().and_then(|o| self.deck.get(o as usize)) {
                a["suitIndex"] = json!(id.suit_index);
                a["rank"] = json!(id.rank);
            }
        }
        a
    }

    fn game_over(&self, player: usize, condition: i64) {
        let over = json!({"type": "gameOver", "endCondition": condition, "playerIndex": player, "votes": []});
        self.emit(over);
        let turn = json!({"type": "turn", "num": self.games[0].state.turn_count - 1, "currentPlayerIndex": -1});
        self.emit(turn);
        // As the site does after the last turn: what each player used and how long the game took. In a replay it
        // is seen one step on from the end.
        let times = json!({
            // Minus the time used, as the site counts an untimed game: down from 0.
            "type": "playerTimes",
            "playerTimes": self.times.borrow().iter().map(|&ms| -(ms as i64)).collect::<Vec<_>>(),
            "duration": self.started.elapsed().as_millis() as u64,
        });
        self.emit(times);
        // A game that ran its course (not one given up) is handed to the app to keep.
        if condition == 1 || condition == 2 {
            let s = &self.games[0].state;
            self.out.push(
                "offlineRecord",
                json!({
                    "version": 1, "startedAt": self.started_at_ms, "durationMs": self.started.elapsed().as_millis() as u64,
                    "variant": self.variant.name, "convention": self.cfg.convention, "seed": self.seed,
                    "names": self.names, "human": self.human, "score": s.score(), "maxScore": s.max_score(),
                    "endCondition": condition, "actions": *self.record.borrow(),
                }),
            );
        }
    }

    /// Ends the game now (the player gave up, or a bot failed): condition 4 is "terminated".
    fn finish(&self, player: usize, condition: i64) {
        self.emit(status(&self.games[self.human]));
        self.game_over(player, condition);
    }
}

/// Builds the convention, so a bad name is reported to the app instead of panicking on the table thread.
pub fn convention(name: &str) -> Result<Arc<dyn Convention>, String> {
    ConventionKind::parse(name)?.build()
}

pub fn spawn(cfg: Config, rx: Receiver<Event>, out: Arc<Outbox>) {
    std::thread::Builder::new()
        .name("offline-table".into())
        .stack_size(16 * 1024 * 1024)
        .spawn(move || run(cfg, rx, out))
        .expect("could not start the table thread");
}

const ADJECTIVES: [&str; 32] = [
    "Wobbly", "Soggy", "Sneaky", "Grumpy", "Fluffy", "Zesty", "Dizzy", "Cranky", "Bouncy", "Sleepy", "Spicy", "Wonky",
    "Jolly", "Mighty", "Squishy", "Rowdy", "Frosty", "Giddy", "Crispy", "Sassy", "Plucky", "Moody", "Cosmic", "Fancy",
    "Lumpy", "Nifty", "Breezy", "Salty", "Clumsy", "Peppy", "Smug", "Dapper",
];

const NOUNS: [&str; 32] = [
    "Walrus", "Pretzel", "Badger", "Noodle", "Goblin", "Waffle", "Penguin", "Muffin", "Llama", "Turnip", "Yeti",
    "Pickle", "Narwhal", "Biscuit", "Wombat", "Dumpling", "Gecko", "Otter", "Parsnip", "Moose", "Cobbler", "Toad",
    "Walnut", "Hamster", "Pancake", "Ferret", "Gnome", "Kipper", "Marmot", "Puffin", "Quokka", "Sprout",
];

/// Kooky, short names for the bots ("Soggy Pretzel"): different every game (the seed changes), never the
/// same twice at a table, never the player's own.
fn bot_names(seed: u64, count: usize, human: &str) -> Vec<String> {
    let mut rng = Rng::new(seed ^ 0xB07_B07);
    let mut names: Vec<String> = Vec::new();
    while names.len() < count {
        let name = format!("{} {}", ADJECTIVES[rng.below(ADJECTIVES.len())], NOUNS[rng.below(NOUNS.len())]);
        if name != human && !names.contains(&name) {
            names.push(name);
        }
    }
    names
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn bot_names_are_short_unique_and_change_with_the_seed() {
        for seed in 0..200 {
            let names = bot_names(seed, 3, "You");
            assert_eq!(names.len(), 3);
            assert!(names.iter().all(|n| n.len() <= 16), "{names:?}");
            let mut sorted = names.clone();
            sorted.sort();
            sorted.dedup();
            assert_eq!(sorted.len(), 3, "{names:?}");
        }
        assert_ne!(bot_names(1, 3, "You"), bot_names(2, 3, "You"));
        assert_eq!(bot_names(7, 3, "You"), bot_names(7, 3, "You"));
    }
}

/// Serves a finished game (an `offlineRecord`) as a replay: the same hand-shake as a real replay, then the
/// whole action list, and nothing more until the app leaves.
pub fn run_replay(record: Value, rx: Receiver<Event>, out: Arc<Outbox>) {
    let wait_for = |command: &str| -> Option<()> {
        loop {
            match rx.recv() {
                Ok(Event::Human(c, _)) if c == command => return Some(()),
                Ok(Event::Human(..)) => continue,
                Ok(Event::Close) | Err(_) => return None,
            }
        }
    };
    out.push("tableStart", json!({"tableID": TABLE_ID, "replay": true}));
    if wait_for("getGameInfo1").is_some() {
        let opts = json!({
            "variantName": record["variant"], "timed": false, "speedrun": false, "cardCycle": false,
            "deckPlays": false, "emptyClues": false, "oneExtraCard": false, "oneLessCard": false,
            "allOrNothing": false, "detrimentalCharacters": false,
        });
        out.push(
            "init",
            json!({
                "tableID": TABLE_ID, "playerNames": record["names"], "ourPlayerIndex": record["human"], "spectating": true,
                "replay": true, "sharedReplay": false, "seed": record["seed"],
                "options": opts, "characterAssignments": [], "characterMetadata": [],
            }),
        );
        if wait_for("getGameInfo2").is_some() {
            out.push("gameActionList", json!({"tableID": TABLE_ID, "list": record["actions"]}));
            // Nothing else is sent; wait until the app goes back.
            while let Ok(Event::Human(..)) = rx.recv() {}
        }
    }
    out.close();
}

pub fn spawn_replay(record: Value, rx: Receiver<Event>, out: Arc<Outbox>) {
    std::thread::Builder::new()
        .name("offline-replay".into())
        .spawn(move || run_replay(record, rx, out))
        .expect("could not start the replay thread");
}
