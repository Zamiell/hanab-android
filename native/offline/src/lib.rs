//! JNI surface of the offline table: start a game, send the app's commands, poll for the table's messages.
mod analysis;
mod rng_cooked;
mod seed;
mod table;

use std::collections::HashMap;
use std::sync::mpsc::{channel, Sender};
use std::sync::atomic::{AtomicUsize, Ordering};
use std::sync::{Arc, Mutex, OnceLock};
use std::time::Duration;

use hanab_bot::basics::Variant;
use hanab_bot::logger::{self, LogLevel};
use jni::objects::{JClass, JString};
use jni::sys::{jlong, jstring};
use jni::JNIEnv;
use serde_json::{json, Value};

use table::{Config, Event, Outbox};

/// The table sizes offline games come in (you and the bots).
pub const MIN_PLAYERS: usize = 2;
pub const MAX_PLAYERS: usize = 5;

struct Running {
    tx: Sender<Event>,
    out: Arc<Outbox>,
}

fn tables() -> &'static Mutex<HashMap<i64, Running>> {
    static TABLES: OnceLock<Mutex<HashMap<i64, Running>>> = OnceLock::new();
    TABLES.get_or_init(|| Mutex::new(HashMap::new()))
}

/// The variant tables are loaded once per process (they do not change, and loading them again while a table
/// reads them is not safe).
fn init_variants(variants: &str, suits: &str) -> Result<(), String> {
    static LOADED: OnceLock<Result<(), String>> = OnceLock::new();
    LOADED
        .get_or_init(|| {
            apply_threads();
            Variant::init(variants, suits)
        })
        .clone()
}

/// The most threads one endgame search gets: more than this rarely helps and the phone has other things to do.
const MAX_ENDGAME_THREADS: usize = 4;

/// How many threads the app allows an endgame search (it lowers this when the battery is low).
static WANTED_THREADS: AtomicUsize = AtomicUsize::new(MAX_ENDGAME_THREADS);

/// Gives the bot's endgame search the allowed number of threads, never more than the phone has cores. The bot picks
/// the same move for any number of threads; fewer only make a hard search likelier to run out of time.
fn apply_threads() {
    let cores = std::thread::available_parallelism().map_or(1, |n| n.get());
    hanab_bot::endgame::solver::set_default_threads(WANTED_THREADS.load(Ordering::Relaxed).min(cores));
}

fn start(variants: &str, suits: &str, config: &str) -> Result<i64, String> {
    logger::set_default_level(LogLevel::Off);
    let c: Value = serde_json::from_str(config).map_err(|e| e.to_string())?;
    init_variants(variants, suits)?;
    let mut cfg = Config {
        variant: c["variant"].as_str().unwrap_or("No Variant").to_string(),
        players: c["players"].as_u64().unwrap_or(3) as usize,
        convention: c["convention"].as_str().unwrap_or("HGroup5").to_string(),
        // The part after the "s" of a seed name like p3v0s50: any text, or a number. Random if none is given.
        seed_suffix: match &c["seed"] {
            Value::String(t) if !t.trim().is_empty() => t.trim().to_string(),
            Value::Number(n) => n.to_string(),
            _ => std::time::SystemTime::now()
                .duration_since(std::time::UNIX_EPOCH)
                .map_or(1, |d| d.as_millis() as u64 % 1_000_000)
                .to_string(),
        },
        human_name: c["humanName"].as_str().unwrap_or("You").to_string(),
        pace_ms: c["paceMs"].as_u64().unwrap_or(900),
    };
    if !(MIN_PLAYERS..=MAX_PLAYERS).contains(&cfg.players) {
        return Err(format!("Offline games are for {MIN_PLAYERS} to {MAX_PLAYERS} players."));
    }
    // Reject a bad variant or convention now rather than on the table's own thread.
    let variant = Variant::get(&cfg.variant)?;
    // A whole seed (p3v0s258) must be for this table; its suffix is what the table builds the name from.
    if let Some((players, variant_id, suffix)) = seed::split_name(&cfg.seed_suffix) {
        if players != cfg.players || variant_id != variant.id {
            return Err(format!("That seed is for {players} players and variant {variant_id}, not this table."));
        }
        cfg.seed_suffix = suffix.to_string();
    }
    table::convention(&cfg.convention)?;

    static NEXT: OnceLock<Mutex<i64>> = OnceLock::new();
    let mut next = NEXT.get_or_init(|| Mutex::new(0)).lock().unwrap();
    *next += 1;
    let handle = *next;

    let (tx, rx) = channel();
    let out = Arc::new(Outbox::new());
    table::spawn(cfg, rx, Arc::clone(&out));
    tables().lock().unwrap().insert(handle, Running { tx, out });
    Ok(handle)
}

fn text(env: &mut JNIEnv, s: &JString) -> String {
    env.get_string(s).map(|j| j.into()).unwrap_or_default()
}

fn to_java(env: &mut JNIEnv, s: &str) -> jstring {
    env.new_string(s).map(|j| j.into_raw()).unwrap_or(std::ptr::null_mut())
}

/// How many threads one endgame search may use from now on (1 to 4); the app lowers it on a low battery.
#[no_mangle]
pub extern "system" fn Java_live_hanab_client_NativeOffline_setThreads<'l>(_env: JNIEnv<'l>, _class: JClass<'l>, threads: jni::sys::jint) {
    WANTED_THREADS.store((threads.max(1) as usize).min(MAX_ENDGAME_THREADS), Ordering::Relaxed);
    apply_threads();
}

/// Starts a table. Returns `{"handle": n}` or `{"error": "..."}`.
#[no_mangle]
pub extern "system" fn Java_live_hanab_client_NativeOffline_start<'l>(
    mut env: JNIEnv<'l>,
    _class: JClass<'l>,
    variants_json: JString<'l>,
    suits_json: JString<'l>,
    config_json: JString<'l>,
) -> jstring {
    let (v, s, c) = (text(&mut env, &variants_json), text(&mut env, &suits_json), text(&mut env, &config_json));
    let result = std::panic::catch_unwind(|| start(&v, &s, &c)).unwrap_or_else(|_| Err("panic".into()));
    let reply = match result {
        Ok(handle) => json!({"handle": handle}),
        Err(e) => json!({"error": e}),
    };
    to_java(&mut env, &reply.to_string())
}

/// Starts a replay of a saved game (the record the table sent when it ended). Returns `{"handle": n}` or
/// `{"error": "..."}`.
#[no_mangle]
pub extern "system" fn Java_live_hanab_client_NativeOffline_startReplay<'l>(
    mut env: JNIEnv<'l>,
    _class: JClass<'l>,
    record_json: JString<'l>,
) -> jstring {
    let record = text(&mut env, &record_json);
    let reply = match serde_json::from_str::<Value>(&record) {
        Ok(record) if record["actions"].is_array() => {
            static NEXT_REPLAY: OnceLock<Mutex<i64>> = OnceLock::new();
            let mut next = NEXT_REPLAY.get_or_init(|| Mutex::new(1 << 40)).lock().unwrap();
            *next += 1;
            let handle = *next;
            let (tx, rx) = channel();
            let out = Arc::new(Outbox::new());
            table::spawn_replay(record, rx, Arc::clone(&out));
            tables().lock().unwrap().insert(handle, Running { tx, out });
            json!({"handle": handle})
        }
        Ok(_) => json!({"error": "That saved game has no moves."}),
        Err(e) => json!({"error": e.to_string()}),
    };
    to_java(&mut env, &reply.to_string())
}

fn analyses() -> &'static Mutex<HashMap<i64, Arc<analysis::Job>>> {
    static ANALYSES: OnceLock<Mutex<HashMap<i64, Arc<analysis::Job>>>> = OnceLock::new();
    ANALYSES.get_or_init(|| Mutex::new(HashMap::new()))
}

/// Starts the bot's analysis of a finished game on a thread of its own and returns its handle (0 if it could not
/// be started). `game_json` is the website's export of the game or an `offlineRecord`. Ask how it is doing with
/// `analyzePoll`; stop it with `analyzeCancel`.
#[no_mangle]
pub extern "system" fn Java_live_hanab_client_NativeOffline_analyzeStart<'l>(
    mut env: JNIEnv<'l>,
    _class: JClass<'l>,
    variants_json: JString<'l>,
    suits_json: JString<'l>,
    game_json: JString<'l>,
    variant: JString<'l>,
    convention: JString<'l>,
) -> jlong {
    let (v, s, g) = (text(&mut env, &variants_json), text(&mut env, &suits_json), text(&mut env, &game_json));
    let (variant, convention) = (text(&mut env, &variant), text(&mut env, &convention));
    if init_variants(&v, &s).is_err() {
        return 0;
    }
    static NEXT_ANALYSIS: OnceLock<Mutex<i64>> = OnceLock::new();
    let mut next = NEXT_ANALYSIS.get_or_init(|| Mutex::new(0)).lock().unwrap();
    *next += 1;
    let handle = *next;
    let job = Arc::new(analysis::Job::default());
    analyses().lock().unwrap().insert(handle, Arc::clone(&job));
    analysis::spawn(job, g, variant, convention);
    handle
}

/// How an analysis is doing: `progress <moves done> <moves in all>` while it runs, then (once) `result <json>` with
/// `{"players", "comments"}` or `{"error"}`. An unknown handle (finished, cancelled) answers `result {"error": ...}`.
#[no_mangle]
pub extern "system" fn Java_live_hanab_client_NativeOffline_analyzePoll<'l>(
    mut env: JNIEnv<'l>,
    _class: JClass<'l>,
    handle: jlong,
) -> jstring {
    let job = analyses().lock().unwrap().get(&handle).cloned();
    let reply = match job {
        None => format!("result {}", json!({"error": "The analysis is not running."})),
        Some(job) => match job.result.lock().unwrap().take() {
            Some(result) => {
                analyses().lock().unwrap().remove(&handle);
                format!("result {result}")
            }
            None => format!(
                "progress {} {}",
                job.moves_done.load(std::sync::atomic::Ordering::Relaxed),
                job.moves_total.load(std::sync::atomic::Ordering::Relaxed),
            ),
        },
    };
    to_java(&mut env, &reply)
}

/// Stops an analysis between moves. Its handle is forgotten; the thread finishes on its own and the answer is dropped.
#[no_mangle]
pub extern "system" fn Java_live_hanab_client_NativeOffline_analyzeCancel<'l>(_env: JNIEnv<'l>, _class: JClass<'l>, handle: jlong) {
    if let Some(job) = analyses().lock().unwrap().remove(&handle) {
        job.cancel.store(true, std::sync::atomic::Ordering::Relaxed);
    }
}

/// Passes one command of the app to the table.
#[no_mangle]
pub extern "system" fn Java_live_hanab_client_NativeOffline_send<'l>(
    mut env: JNIEnv<'l>,
    _class: JClass<'l>,
    handle: jlong,
    command: JString<'l>,
    data_json: JString<'l>,
) {
    let (command, data) = (text(&mut env, &command), text(&mut env, &data_json));
    let data = serde_json::from_str(&data).unwrap_or(Value::Null);
    if let Some(t) = tables().lock().unwrap().get(&handle) {
        let _ = t.tx.send(Event::Human(command, data));
    }
}

/// The next message of the table (`command {json}`), waiting up to `timeout_ms`. Null if there is none yet;
/// the empty string once the table has finished and everything has been read.
#[no_mangle]
pub extern "system" fn Java_live_hanab_client_NativeOffline_poll<'l>(
    mut env: JNIEnv<'l>,
    _class: JClass<'l>,
    handle: jlong,
    timeout_ms: jlong,
) -> jstring {
    let out = match tables().lock().unwrap().get(&handle) {
        Some(t) => Arc::clone(&t.out),
        None => return to_java(&mut env, ""),
    };
    match out.pop(Duration::from_millis(timeout_ms.max(0) as u64)) {
        Some(msg) => to_java(&mut env, &msg),
        None if out.closed.load(std::sync::atomic::Ordering::SeqCst) => to_java(&mut env, ""),
        None => std::ptr::null_mut(),
    }
}

/// Stops the table (a game in progress is abandoned).
#[no_mangle]
pub extern "system" fn Java_live_hanab_client_NativeOffline_close<'l>(_env: JNIEnv<'l>, _class: JClass<'l>, handle: jlong) {
    if let Some(t) = tables().lock().unwrap().remove(&handle) {
        let _ = t.tx.send(Event::Close);
        t.out.close();
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    const VARIANTS: &str = include_str!("../../../app/src/main/assets/variants.json");
    const SUITS: &str = include_str!("../../../app/src/main/assets/suits.json");


    fn send(handle: i64, command: &str, data: Value) {
        tables().lock().unwrap().get(&handle).unwrap().tx.send(Event::Human(command.into(), data)).unwrap();
    }

    /// Plays the app's part: the hand-shake, then a simple human who discards (or clues at 8 clues).
    /// Returns an error instead of panicking, so a sweep can report every failing variant.
    fn play_scripted(variant: &str, players: usize, seed: u64, check_rules: bool) -> Result<Value, String> {
        let config = format!(r#"{{"variant":{variant:?},"players":{players},"convention":"HGroup1","seed":{seed},"paceMs":0}}"#);
        let h = start(VARIANTS, SUITS, &config)?;
        // The rank clues the variant has (all of them unless it lists some); a bot giving another one is a failure.
        let all: Vec<Value> = serde_json::from_str(VARIANTS).unwrap();
        let ranks: Vec<i64> = all
            .iter()
            .find(|v| v["name"] == variant)
            .and_then(|v| v["clueRanks"].as_array())
            .map(|r| r.iter().filter_map(Value::as_i64).collect())
            .unwrap_or_default();
        let result = std::panic::catch_unwind(|| scripted_game(h, players, check_rules, &ranks)).unwrap_or_else(|_| Err("panic".into()));
        let _ = tables().lock().unwrap().remove(&h);
        result
    }

    fn scripted_game(h: i64, players: usize, check_rules: bool, ranks: &[i64]) -> Result<Value, String> {
        let wait = |what: &str| -> Result<Value, String> {
            let out = tables().lock().unwrap().get(&h).map(|t| Arc::clone(&t.out)).ok_or("gone")?;
            let msg = out.pop(Duration::from_secs(60)).ok_or_else(|| {
                format!("went quiet waiting for {what} (closed: {})", out.closed.load(std::sync::atomic::Ordering::SeqCst))
            })?;
            let (command, data) = msg.split_once(' ').unwrap_or((&msg, "null"));
            Ok(json!({"command": command, "data": serde_json::from_str::<Value>(data).unwrap_or(Value::Null)}))
        };
        let expect = |what: &str, command: &str| -> Result<Value, String> {
            let m = wait(what)?;
            if m["command"] == command { Ok(m) } else { Err(format!("expected {command}, got {}", m["command"])) }
        };
        expect("tableStart", "tableStart")?;
        send(h, "getGameInfo1", json!({"tableID": 1}));
        let init = expect("init", "init")?;
        if init["data"]["playerNames"].as_array().map_or(0, Vec::len) != players {
            return Err("wrong number of players".into());
        }
        // The human sits at a random seat; seat 0 starts, so sometimes a bot goes first.
        let me = init["data"]["ourPlayerIndex"].as_i64().ok_or("no seat")?;
        let other = (me + 1) % players as i64;
        send(h, "getGameInfo2", json!({"tableID": 1}));
        let list = expect("gameActionList", "gameActionList")?;
        let actions = list["data"]["list"].as_array().ok_or("no list")?.clone();
        let hand = if players <= 3 { 5 } else { 4 };
        if actions.len() != players * hand + 2 {
            return Err(format!("{} dealt actions", actions.len()));
        }
        let mut mine: Vec<i64> = actions
            .iter()
            .filter(|a| a["type"] == "draw" && a["playerIndex"] == me)
            .map(|a| a["order"].as_i64().unwrap())
            .collect();
        let mut clues = 8;
        send(h, "loaded", json!({"tableID": 1}));

        // Rules are enforced: no discarding at 8 clues, and no clue to yourself. Probed on the first own turn.
        let mut probe = check_rules;
        let probe_rules = |mine: &Vec<i64>, clues: i64| -> Result<(), String> {
            if clues >= 8 {
                send(h, "action", json!({"tableID": 1, "type": 1, "target": mine[0], "value": 0}));
                expect("a warning", "warning")?;
            }
            send(h, "action", json!({"tableID": 1, "type": 2, "target": me, "value": 0}));
            expect("a warning", "warning")?;
            Ok(())
        };

        let mut value = 1;
        let mut empty_clues = 0;
        let mut moves = 0;
        // If the human starts, that turn came in the action list, not as a live message.
        if me == 0 {
            if probe {
                probe_rules(&mine, clues)?;
                probe = false;
            }
            send(h, "action", json!({"tableID": 1, "type": 3, "target": other, "value": value}));
        }
        loop {
            let m = wait("the next message")?;
            match m["command"].as_str().unwrap() {
                "warning" => {
                    // That clue touched nothing: try the next rank, and when none touches anything (a hand of cards
                    // that cannot be clued) play a card, which is always allowed.
                    value = value % 5 + 1;
                    empty_clues += 1;
                    if empty_clues > 5 {
                        empty_clues = 0;
                        send(h, "action", json!({"tableID": 1, "type": 0, "target": mine[0], "value": 0}));
                    } else {
                        send(h, "action", json!({"tableID": 1, "type": 3, "target": other, "value": value}));
                    }
                }
                "offlineRecord" => return Ok(m["data"].clone()),
                "gameAction" => {
                    let a = &m["data"]["action"];
                    match a["type"].as_str().unwrap() {
                        "status" => clues = a["clues"].as_i64().unwrap(),
                        "clue" if a["giver"] != me && a["clue"]["type"] == 1 && !ranks.is_empty()
                            && !ranks.contains(&a["clue"]["value"].as_i64().unwrap_or(0)) =>
                        {
                            return Err(format!("a bot gave the rank clue {}, which the variant does not have", a["clue"]["value"]));
                        }
                        "draw" if a["playerIndex"] == me => mine.push(a["order"].as_i64().unwrap()),
                        "play" | "discard" if a["playerIndex"] == me => mine.retain(|&o| o != a["order"].as_i64().unwrap()),
                        "gameOver" => {}
                        "turn" if a["currentPlayerIndex"] == me => {
                            moves += 1;
                            if probe {
                                probe_rules(&mine, clues)?;
                                probe = false;
                            }
                            if mine.is_empty() {
                                return Err("the human has no cards but it is their turn".into());
                            }
                            if clues >= 8 {
                                send(h, "action", json!({"tableID": 1, "type": 3, "target": other, "value": value}));
                            } else {
                                send(h, "action", json!({"tableID": 1, "type": 1, "target": mine[0], "value": 0}));
                            }
                        }
                        _ => {}
                    }
                }
                _ => {}
            }
            if moves > 300 {
                return Err("the game did not end".into());
            }
        }
    }

    /// The game is over the moment no more points can be gained (every copy of a card the stacks still need is
    /// gone), not when the last player has had their turn.
    #[test]
    fn ends_as_soon_as_no_more_points_can_be_gained() {
        for players in 2..=3 {
            for seed in 1..=4 {
                let record = play_scripted("No Variant", players, seed, false).unwrap();
                let copies = [0, 3, 2, 2, 2, 1];
                let mut gone = [[0usize; 6]; 5];
                let mut stacks = [0usize; 5];
                let mut max = [5usize; 5];
                let actions = record["actions"].as_array().unwrap();
                for (i, a) in actions.iter().enumerate() {
                    let (Some(suit), Some(rank)) = (a["suitIndex"].as_u64(), a["rank"].as_u64()) else { continue };
                    let (suit, rank) = (suit as usize, rank as usize);
                    match a["type"].as_str().unwrap() {
                        "play" => stacks[suit] = rank,
                        "discard" => {
                            gone[suit][rank] += 1;
                            if gone[suit][rank] == copies[rank] {
                                max[suit] = max[suit].min(rank - 1);
                            }
                        }
                        _ => continue,
                    }
                    if stacks.iter().sum::<usize>() == max.iter().sum::<usize>() {
                        let later = actions[i + 1..].iter().any(|b| matches!(b["type"].as_str(), Some("play" | "discard" | "clue")));
                        assert!(!later, "{players} players, seed {seed}: the game went on after no point could be gained");
                    }
                }
            }
        }
    }

    /// Four bots and you: four cards each, five seats, and the game still runs to its end.
    #[test]
    fn plays_a_whole_game_with_four_bots() {
        let record = play_scripted("No Variant", 5, 11, true).unwrap();
        assert_eq!(record["names"].as_array().unwrap().len(), 5);
        assert!(record["endCondition"] == 1 || record["endCondition"] == 2);
    }

    #[test]
    fn plays_a_whole_game_with_bots() {
        let record = play_scripted("No Variant", 3, 5, true).unwrap();
        assert_eq!(record["variant"], "No Variant");
        assert_eq!(record["names"].as_array().unwrap().len(), 3);
        assert!(record["endCondition"] == 1 || record["endCondition"] == 2);
        let actions = record["actions"].as_array().unwrap();
        assert!(actions.len() > 17);
        // The last turn, then what the site sends after it: each player's time and how long the game took.
        let times = actions.last().unwrap();
        assert_eq!(times["type"], "playerTimes");
        let used = times["playerTimes"].as_array().unwrap();
        assert_eq!(used.len(), 3);
        // An untimed game counts down from 0, like the site: what a player took is minus the number.
        assert!(used.iter().all(|t| t.as_i64().unwrap() <= 0));
        assert!(used.iter().any(|t| t.as_i64().unwrap() < 0));
        assert!(times["duration"].as_u64().unwrap() > 0);
        // What the players used adds up to how long the game took (the bots' thinking pause is theirs).
        let total: i64 = used.iter().map(|t| -t.as_i64().unwrap()).sum();
        assert_eq!(total as u64, times["duration"].as_u64().unwrap());
        assert_eq!(record["durationMs"], times["duration"]);
        // The bots' notes are kept with the game (the human wrote none), one list per bot, a slot per card.
        let notes = record["notes"].as_array().unwrap();
        assert!(!notes.is_empty() && notes.len() <= 2, "{notes:?}");
        assert!(notes.iter().all(|n| n["name"] != record["names"][record["human"].as_u64().unwrap() as usize]));
        assert!(notes.iter().any(|n| n["notes"].as_array().unwrap().iter().any(|t| !t.as_str().unwrap().is_empty())));
        assert_eq!(actions[actions.len() - 2]["currentPlayerIndex"], -1);
        // A replay shows every card, including the ones the human was dealt.
        assert!(actions.iter().filter(|a| a["type"] == "draw").all(|a| a["suitIndex"] != -1));

        // ...and the saved game plays back through the same hand-shake as a real replay.
        let (tx, rx) = channel();
        let out = Arc::new(Outbox::new());
        table::spawn_replay(record.clone(), rx, Arc::clone(&out));
        let next = || out.pop(Duration::from_secs(10)).expect("the replay went quiet");
        assert!(next().starts_with("tableStart"));
        tx.send(Event::Human("getGameInfo1".into(), Value::Null)).unwrap();
        let init = next();
        assert!(init.starts_with("init") && init.contains("\"spectating\":true"));
        tx.send(Event::Human("getGameInfo2".into(), Value::Null)).unwrap();
        let list: Value = serde_json::from_str(next().strip_prefix("gameActionList ").unwrap()).unwrap();
        assert_eq!(list["list"].as_array().unwrap().len(), actions.len());
        // The bots' notes follow, for the replay to show.
        let shown: Value = serde_json::from_str(next().strip_prefix("noteList ").unwrap()).unwrap();
        assert_eq!(shown["notes"], record["notes"]);
        tx.send(Event::Close).unwrap();
    }

    /// The variants with special rules that the app plays: Rainbow-Ones, White-Ones, scarce ones, critical fours and
    /// Clue Starved all play through, and Clue Starved reports clues at twice their value, as the server does.
    #[test]
    fn plays_the_variants_with_special_rules() {
        for name in ["Rainbow-Ones (5 Suits)", "White-Ones (5 Suits)", "Scarce Ones (5 Suits)", "Critical Fours (5 Suits)"] {
            let record = play_scripted(name, 3, 1, false).unwrap_or_else(|e| panic!("{name}: {e}"));
            let max = record["actions"].as_array().unwrap().iter().filter(|a| a["type"] == "status").map(|a| a["clues"].as_i64().unwrap()).max();
            assert!(max.unwrap() <= 8, "{name}: clues {max:?}");
        }
        let record = play_scripted("Clue Starved (5 Suits)", 3, 1, false).unwrap();
        let counts: Vec<i64> =
            record["actions"].as_array().unwrap().iter().filter(|a| a["type"] == "status").map(|a| a["clues"].as_i64().unwrap()).collect();
        assert!(counts.iter().all(|&c| (0..=16).contains(&c)), "{counts:?}");
        assert!(counts.iter().any(|&c| c > 8), "clues should be counted at twice their value: {counts:?}");
        // A clue costs two, so the count drops by two when one is given.
        assert!(counts.windows(2).any(|w| w[1] == w[0] - 2), "{counts:?}");
    }

    /// The table deals what the website dealt for the same seed: the cards the human can see are the real ones.
    #[test]
    fn a_seeded_table_deals_the_sites_cards() {
        let games: Vec<Value> = serde_json::from_str(include_str!("../tests/data/seeded_games.json")).unwrap();
        let g = games.iter().find(|g| g["variant"] == "No Variant" && g["players"] == 3).unwrap();
        let seed = g["seed"].as_str().unwrap(); // e.g. "p3v0s861"
        let suffix = seed.split_once('s').unwrap().1;
        let config = format!(r#"{{"variant":"No Variant","players":3,"convention":"HGroup1","seed":"{suffix}","paceMs":0}}"#);
        let h = start(VARIANTS, SUITS, &config).unwrap();
        let out = tables().lock().unwrap().get(&h).map(|t| Arc::clone(&t.out)).unwrap();
        let next = || {
            let m = out.pop(Duration::from_secs(10)).expect("the table went quiet");
            let (c, d) = m.split_once(' ').unwrap();
            (c.to_string(), serde_json::from_str::<Value>(d).unwrap())
        };
        assert_eq!(next().0, "tableStart");
        send(h, "getGameInfo1", json!({"tableID": 1}));
        let (_, init) = next();
        assert_eq!(init["seed"], seed);
        send(h, "getGameInfo2", json!({"tableID": 1}));
        let (_, list) = next();
        let mut seen = 0;
        for a in list["list"].as_array().unwrap().iter().filter(|a| a["type"] == "draw" && a["suitIndex"] != -1) {
            let card = &g["deck"][a["order"].as_u64().unwrap() as usize];
            assert_eq!((a["suitIndex"].as_u64().unwrap(), a["rank"].as_u64().unwrap()), (card[0].as_u64().unwrap(), card[1].as_u64().unwrap()));
            seen += 1;
        }
        assert_eq!(seen, 10); // the two other hands of five
        send(h, "tableUnattend", json!({"tableID": 1}));
        let _ = tables().lock().unwrap().remove(&h);
    }

    /// The bot comments on a real game of the site and on a game played offline.
    #[test]
    fn analyses_a_real_game_and_an_offline_game() {
        init_variants(VARIANTS, SUITS).unwrap();
        let real = analysis::analyze_blocking(include_str!("../tests/data/export_2046249.json"), "No Variant", "HGroup5").unwrap();
        let real: Value = serde_json::from_str(&real).unwrap();
        assert_eq!(real["players"].as_array().unwrap().len(), 3);
        assert!(real["comments"].is_array());

        let record = play_scripted("No Variant", 3, 7, false).unwrap();
        let mine = analysis::analyze_blocking(&record.to_string(), "No Variant", "HGroup1").unwrap();
        let mine: Value = serde_json::from_str(&mine).unwrap();
        // The scripted human only discards, so the bot has things to say about it.
        let comments = mine["comments"].as_array().unwrap();
        assert!(!comments.is_empty());
        assert!(comments.iter().all(|c| c["text"].is_string() && c["player"].is_number()));

        assert!(analysis::analyze_blocking("not json", "No Variant", "HGroup1").is_err());
        assert!(analysis::analyze_blocking(&record.to_string(), "No Variant", "NoSuchConvention").is_err());
    }

    /// A running analysis reports how many moves it has looked at, and one that is cancelled stops with an error.
    #[test]
    fn an_analysis_reports_progress_and_can_be_cancelled() {
        init_variants(VARIANTS, SUITS).unwrap();
        let game = include_str!("../tests/data/export_2046249.json").to_string();
        let wait = |job: &analysis::Job| -> Value {
            for _ in 0..6000 {
                if let Some(text) = job.result.lock().unwrap().clone() {
                    return serde_json::from_str(&text).unwrap();
                }
                std::thread::sleep(Duration::from_millis(50));
            }
            panic!("the analysis did not finish");
        };

        let job = Arc::new(analysis::Job::default());
        analysis::spawn(Arc::clone(&job), game.clone(), "No Variant".into(), "HGroup5".into());
        let done = wait(&job);
        assert!(done["comments"].is_array(), "{done}");
        let total = job.moves_total.load(std::sync::atomic::Ordering::Relaxed);
        assert!(total > 0);
        assert_eq!(job.moves_done.load(std::sync::atomic::Ordering::Relaxed), total);

        let stopped = Arc::new(analysis::Job::default());
        stopped.cancel.store(true, std::sync::atomic::Ordering::Relaxed);
        analysis::spawn(Arc::clone(&stopped), game, "No Variant".into(), "HGroup5".into());
        let result = wait(&stopped);
        assert!(result["error"].as_str().unwrap().contains("cancelled"), "{result}");
    }

    /// You made the table, so you sit where the site seated the creator of a real game on this seed.
    #[test]
    fn the_human_sits_where_the_site_seated_the_creator() {
        // Game #2046249 on hanab.live, seed p3v0s258: its creator was in seat 0.
        let config = r#"{"variant":"No Variant","players":3,"convention":"HGroup1","seed":"258","paceMs":0}"#;
        let h = start(VARIANTS, SUITS, config).unwrap();
        let out = tables().lock().unwrap().get(&h).map(|t| Arc::clone(&t.out)).unwrap();
        let mut take = || out.pop(Duration::from_secs(10)).expect("the table went quiet");
        assert!(take().starts_with("tableStart"));
        send(h, "getGameInfo1", json!({"tableID": 1}));
        let init = take();
        assert!(init.contains("\"ourPlayerIndex\":0"), "{init}");
        assert!(init.contains("\"seed\":\"p3v0s258\""), "{init}");
        send(h, "tableUnattend", json!({"tableID": 1}));
        let _ = tables().lock().unwrap().remove(&h);
    }

    /// A whole seed as the site shows it works as well as its suffix, and must be for this table.
    #[test]
    fn a_whole_seed_is_accepted_when_it_fits_the_table() {
        let whole = r#"{"variant":"No Variant","players":3,"convention":"HGroup1","seed":"p3v0s258","paceMs":0}"#;
        let h = start(VARIANTS, SUITS, whole).unwrap();
        let out = tables().lock().unwrap().get(&h).map(|t| Arc::clone(&t.out)).unwrap();
        let mut take = || out.pop(Duration::from_secs(10)).expect("the table went quiet");
        assert!(take().starts_with("tableStart"));
        send(h, "getGameInfo1", json!({"tableID": 1}));
        let init = take();
        // Not "p3v0sp3v0s258": the same seed, so the same deal, as typing just 258.
        assert!(init.contains("\"seed\":\"p3v0s258\""), "{init}");
        send(h, "tableUnattend", json!({"tableID": 1}));
        let _ = tables().lock().unwrap().remove(&h);

        let wrong_size = r#"{"variant":"No Variant","players":4,"convention":"HGroup1","seed":"p3v0s258","paceMs":0}"#;
        assert!(start(VARIANTS, SUITS, wrong_size).is_err());
        let wrong_variant = r#"{"variant":"6 Suits","players":3,"convention":"HGroup1","seed":"p3v0s258","paceMs":0}"#;
        assert!(start(VARIANTS, SUITS, wrong_variant).is_err());
    }

    /// The variants the app offers offline (the same rule as the picker: the app can show them and the bots can play them).
    /// The keys of a variant in variants.json that the app plays: its name and suits, and the special rules it models
    /// (Rainbow-Ones, White-Ones, critical ranks, scarce ones, Clue Starved). Keep this with `Variants.kt`.
    const MODELLED_KEYS: [&str; 14] = [
        "id", "newID", "name", "suits", "specialRank", "specialRankAllClueColors", "specialRankNoClueColors", "criticalRank",
        "scarceOnes", "clueStarved", "clueRanks", "specialRankAllClueRanks", "specialRankNoClueRanks", "specialRankDeceptive",
    ];

    fn offered_variants() -> Vec<String> {
        let all: Vec<Value> = serde_json::from_str(VARIANTS).unwrap();
        let suits: Vec<Value> = serde_json::from_str(SUITS).unwrap();
        let known: Vec<&str> = suits.iter().map(|s| s["name"].as_str().unwrap()).collect();
        let unusable: Vec<&str> = Vec::new();
        let bot_no = [
            "Blind", "Mute", "Ambiguous", "Dual-Color", "Synesthesia", "Mix", "Funnels", "Chimneys", "Matryoshka",
            "Alternating", "Odds", "Cow", "Duck", "Reversed", "Throw", "Up or Down", "Sudoku",
        ];
        all.iter()
            .filter(|v| {
                let plain = v.as_object().unwrap().keys().all(|k| MODELLED_KEYS.contains(&k.as_str()));
                let suits_ok = v["suits"].as_array().unwrap().iter().all(|s| {
                    let s = s.as_str().unwrap();
                    known.contains(&s) && !unusable.contains(&s)
                });
                plain && suits_ok && !bot_no.iter().any(|b| v["name"].as_str().unwrap().contains(b))
            })
            .map(|v| v["name"].as_str().unwrap().to_string())
            .collect()
    }

    /// Plays variants the app does not offer yet to see whether the bots cope: `PROBE_KEYS=clueRanks` adds those keys
    /// to the ones allowed, `PROBE_NAME` picks variants by name, `PROBE_STEP` takes every n-th. Prints the failures.
    /// Run with `--ignored --nocapture`.
    #[test]
    #[ignore]
    fn probe_unoffered_variants() {
        let all: Vec<Value> = serde_json::from_str(VARIANTS).unwrap();
        let extra: Vec<String> = std::env::var("PROBE_KEYS").unwrap_or_default().split(',').map(str::to_string).collect();
        let step: usize = std::env::var("PROBE_STEP").ok().and_then(|v| v.parse().ok()).unwrap_or(1);
        let name_part = std::env::var("PROBE_NAME").unwrap_or_default();
        let bot_no = [
            "Blind", "Mute", "Ambiguous", "Dual-Color", "Synesthesia", "Mix", "Funnels", "Chimneys", "Matryoshka",
            "Alternating", "Odds", "Cow", "Duck", "Reversed", "Throw", "Up or Down", "Sudoku",
        ];
        let mut failures = Vec::new();
        let mut played = 0;
        for (i, v) in all.iter().enumerate() {
            let name = v["name"].as_str().unwrap();
            let keys_ok = v.as_object().unwrap().keys().all(|k| MODELLED_KEYS.contains(&k.as_str()) || extra.contains(k));
            if !keys_ok || i % step != 0 || !name.contains(&name_part) || bot_no.iter().any(|b| name.contains(b)) {
                continue;
            }
            let players = MIN_PLAYERS + played % (MAX_PLAYERS - MIN_PLAYERS + 1);
            played += 1;
            match play_scripted(name, players, played as u64, false) {
                Ok(_) => eprintln!("ok   {name} ({players}p)"),
                Err(e) => {
                    eprintln!("FAIL {name} ({players}p): {e}");
                    failures.push(format!("{name} ({players}p): {e}"));
                }
            }
        }
        eprintln!("played {played}, {} failed", failures.len());
        assert!(failures.is_empty(), "{}", failures.join("
"));
    }

    /// Every offered variant at 2 and 3 players (`SWEEP_PLAYERS` picks the sizes) with a few seeds, printing each result
    /// and, at the end, every failure. `SWEEP_SEEDS` sets how many seeds (default 2) and `SWEEP_FROM` where to start.
    /// Run with `--ignored --nocapture`.
    #[test]
    #[ignore]
    fn sweep_small_tables() {
        let seeds: u64 = std::env::var("SWEEP_SEEDS").ok().and_then(|v| v.parse().ok()).unwrap_or(2);
        let from: usize = std::env::var("SWEEP_FROM").ok().and_then(|v| v.parse().ok()).unwrap_or(0);
        // The table sizes to play, like SWEEP_PLAYERS=2,3 (the default) or 4.
        let sizes: Vec<usize> = std::env::var("SWEEP_PLAYERS")
            .map(|v| v.split(',').filter_map(|p| p.trim().parse().ok()).collect())
            .unwrap_or_else(|_| vec![2, 3]);
        let mut failures = Vec::new();
        // SWEEP_SPECIAL=1 plays only the variants with special rules (Rainbow-Ones, Clue Starved, ...).
        let special_only = std::env::var("SWEEP_SPECIAL").is_ok();
        let step: usize = std::env::var("SWEEP_STEP").ok().and_then(|v| v.parse().ok()).unwrap_or(1);
        let all: Vec<Value> = serde_json::from_str(VARIANTS).unwrap();
        let has_rules = |name: &str| {
            all.iter().any(|v| v["name"] == name && v.as_object().unwrap().keys().any(|k| !["id", "newID", "name", "suits"].contains(&k.as_str())))
        };
        for (n, name) in offered_variants().iter().enumerate().skip(from) {
            if special_only && !has_rules(name) {
                continue;
            }
            // SWEEP_NAME=Prism plays only the variants with that in their name.
            if std::env::var("SWEEP_NAME").is_ok_and(|w| !name.contains(&w)) {
                continue;
            }
            // SWEEP_STEP=12 plays every 12th variant only (a sample for slow table sizes).
            if step > 1 && n % step != 0 {
                continue;
            }
            for &players in &sizes {
                for seed in 1..=seeds {
                    let started = std::time::Instant::now();
                    let result = play_scripted(name, players, seed, false);
                    eprintln!(
                        "{n:>3} {name} ({players}p, seed {seed}): {} in {:.1}s {}",
                        if result.is_ok() { "ok" } else { "FAILED" },
                        started.elapsed().as_secs_f32(),
                        result.as_ref().err().map_or(String::new(), |e| format!("-- {e}")),
                    );
                    if let Err(e) = result {
                        failures.push(format!("{name} ({players}p, seed {seed}): {e}"));
                    }
                }
            }
        }
        eprintln!("SWEEP DONE: {} failures", failures.len());
        for f in &failures {
            eprintln!("FAILED {f}");
        }
    }

    /// Games one after the other in this process, named by `PROBE="variant|players|seed;variant|players|seed"`,
    /// printing what went wrong. Run with `--ignored --nocapture`.
    #[test]
    #[ignore]
    fn probe_games() {
        let probe = std::env::var("PROBE").expect("set PROBE=\"variant|players|seed;...\"");
        for game in probe.split(';') {
            let parts: Vec<&str> = game.split('|').collect();
            let result = play_scripted(parts[0], parts[1].parse().unwrap(), parts[2].parse().unwrap(), false);
            eprintln!("PROBE RESULT {game}: {:?}", result.as_ref().map(|_| "ok"));
        }
    }

    /// Every variant the app offers offline, with every table size. Slow; run with `--ignored`.
    #[test]
    #[ignore]
    fn plays_every_offered_variant() {
        let all: Vec<Value> = serde_json::from_str(VARIANTS).unwrap();
        let suits: Vec<Value> = serde_json::from_str(SUITS).unwrap();
        let known: Vec<&str> = suits.iter().map(|s| s["name"].as_str().unwrap()).collect();
        let unusable: Vec<&str> = Vec::new();
        let bot_no = [
            "Blind", "Mute", "Ambiguous", "Dual-Color", "Synesthesia", "Mix", "Funnels", "Chimneys", "Matryoshka",
            "Alternating", "Odds", "Cow", "Duck", "Reversed", "Throw", "Up or Down", "Sudoku",
        ];
        let mut failures = Vec::new();
        let mut played = 0;
        for v in &all {
            let name = v["name"].as_str().unwrap();
            let plain = v.as_object().unwrap().keys().all(|k| MODELLED_KEYS.contains(&k.as_str()));
            let suits_ok = v["suits"].as_array().unwrap().iter().all(|s| {
                let s = s.as_str().unwrap();
                known.contains(&s) && !unusable.contains(&s)
            });
            if !plain || !suits_ok || bot_no.iter().any(|b| name.contains(b)) {
                continue;
            }
            let players = MIN_PLAYERS + played % (MAX_PLAYERS - MIN_PLAYERS + 1);
            played += 1;
            if let Err(e) = play_scripted(name, players, played as u64, false) {
                failures.push(format!("{name} ({players}p): {e}"));
            }
        }
        eprintln!("played {played} variants");
        assert!(failures.is_empty(), "{} failures:
{}", failures.len(), failures.join("
"));
    }
}
