@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class, androidx.compose.foundation.ExperimentalFoundationApi::class)

package live.hanab.client.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.material3.OutlinedTextField
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.exclude
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.material3.FilterChip
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.animation.core.Animatable
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import live.hanab.client.AnimSpeed
import live.hanab.client.AppSettings
import live.hanab.client.LobbyUiState
import live.hanab.client.LobbyViewModel
import live.hanab.client.game.Card
import live.hanab.client.game.formatClock
import live.hanab.client.game.Knowledge
import live.hanab.client.game.GameState
import live.hanab.client.game.Phase

/** Muted version of a colour for dead cards: still readable as that suit, but clearly washed out. */
fun dull(c: Color): Color = androidx.compose.ui.graphics.lerp(c, Color(0xFF3A3A3A), 0.6f)

/**
 * White text with a thin black outline, so numbers stay readable on every suit colour (white alone
 * disappears on Yellow). The outline is the text stamped in black in 8 directions at a small pixel
 * offset, which stays crisp at any size (a font stroke turns into a dark halo on small digits).
 */
@Composable
fun OutlinedText(
    text: String,
    fontSize: androidx.compose.ui.unit.TextUnit,
    modifier: Modifier = Modifier,
    fill: Color =    Color.White,
    fontWeight: FontWeight = FontWeight.Bold,
) {
    if (text.isEmpty()) return
    val measurer = androidx.compose.ui.text.rememberTextMeasurer()
    val density = androidx.compose.ui.platform.LocalDensity.current
    val layout = remember(text, fontSize, fontWeight) {
        measurer.measure(text, androidx.compose.ui.text.TextStyle(fontSize = fontSize, fontWeight = fontWeight))
    }
    val style = AppSettings.outline
    val r = if (style.factor == 0f) 0f
    else (with(density) { fontSize.toPx() } * style.factor).coerceIn(1f, style.maxPx)
    val w = with(density) { (layout.size.width + 2 * r).toDp() }
    val h = with(density) { (layout.size.height + 2 * r).toDp() }
    androidx.compose.foundation.Canvas(modifier.size(w, h)) {
        if (r > 0f) for (dx in -1..1) for (dy in -1..1) {
            if (dx == 0 && dy == 0) continue
            drawText(layout, color = Color.Black, topLeft = Offset(r + dx * r, r + dy * r))
        }
        drawText(layout, color = fill, topLeft = Offset(r, r))
    }
}
/** Solid colour for a suit or clue colour name, from suits.json (Rainbow's fills collapse to one colour). */
fun suitColor(name: String): Color {
    val info = live.hanab.client.game.Variants.info(name)
    val fills = if (AppSettings.colorblind && info.fillsColorblind.isNotEmpty()) info.fillsColorblind else info.fills
    return when {
        fills.size > 1 -> Color(0xFFBA68C8)
        fills.size == 1 -> Color(fills[0])
        else -> Color(0xFF616161)
    }
}

/**
 * The colour of a prism card, which depends on its rank: the clue colour that goes with the rank mixed half and half
 * with white (black for the dark prism), as the website paints it. Null for any other card.
 */
fun prismColor(g: live.hanab.client.game.GameState, suit: Int, rank: Int): Color? {
    val info = g.suitInfos.getOrNull(suit)?.takeIf { it.prism } ?: return null
    val i = g.prismIndex(rank)
    if ((rank !in 1..5 && rank != live.hanab.client.game.START_RANK) || i < 0) return null
    return androidx.compose.ui.graphics.lerp(suitColor(g.clueColors[i]), if (info.oneOfEach) Color.Black else Color.White, 0.5f)
}

/** Like [suitColor], but multi-colour suits (Rainbow) get a left-to-right gradient. */
fun suitBrush(name: String): androidx.compose.ui.graphics.Brush {
    val fills = live.hanab.client.game.Variants.info(name).fills
    return if (fills.size > 1) androidx.compose.ui.graphics.Brush.horizontalGradient(fills.map { Color(it) })
    else androidx.compose.ui.graphics.SolidColor(suitColor(name))
}
/** [suitBrush] for a card that is trash: each colour toned down, so a Rainbow stays a (dim) gradient, not one flat colour. */
fun dullBrush(name: String): androidx.compose.ui.graphics.Brush {
    val fills = live.hanab.client.game.Variants.info(name).fills
    return if (fills.size > 1) androidx.compose.ui.graphics.Brush.horizontalGradient(fills.map { dull(Color(it)) })
    else androidx.compose.ui.graphics.SolidColor(dull(suitColor(name)))
}

@Composable
fun GameScreen(ui: LobbyUiState, g: GameState, vm: LobbyViewModel) {
    // Stop the phone sleeping (and dropping the connection) mid-game, if the setting is on.
    val view = androidx.compose.ui.platform.LocalView.current
    androidx.compose.runtime.DisposableEffect(AppSettings.keepAwake) {
        view.keepScreenOn = AppSettings.keepAwake
        onDispose { view.keepScreenOn = false }
    }
    // Everyone else watching this table; the eye shows only when there is someone.
    val watchers = ui.tables[g.tableID]?.spectators.orEmpty().map { it.name }
        .filter { !it.equals(ui.me?.username, ignoreCase = true) }
    var tab by remember { mutableIntStateOf(0) }
    var seen by remember { mutableIntStateOf(0) }
    val total = ui.tableChat.size
    LaunchedEffect(tab, total) { if (tab == 1) seen = total }
    val unread = if (tab == 1) 0 else (total - seen).coerceAtLeast(0)

    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize()) {
        // Wide screens (tablets, phones in landscape) show the board and the table chat side by side.
        val wide = maxWidth >= 700.dp
        // On a tall tablet there is room to make the whole board bigger.
        val scale = if (wide && maxHeight >= 600.dp) 1.25f else 1f
        Surface(Modifier.fillMaxSize(), color = Color.Transparent, contentColor = MaterialTheme.colorScheme.onBackground) {
            // Safe-drawing insets without the keyboard: the board must not resize when it opens (for a note).
            // The chat panel makes room for the keyboard itself.
            Column(Modifier.windowInsetsPadding(WindowInsets.safeDrawing.exclude(WindowInsets.ime))) {
                ui.error?.let { err ->
                    // Gone after a few seconds, or when tapped.
                    LaunchedEffect(err) { kotlinx.coroutines.delay(6_000); vm.clearError() }
                    ErrorBanner(err, vm::clearError)
                }
                if (ui.offline) {
                    // No chat without a server.
                    GameBoard(g, vm, ui)
                } else if (wide) {
                    Row(Modifier.fillMaxSize()) {
                        Box(Modifier.weight(1.5f)) {
                            val d = androidx.compose.ui.platform.LocalDensity.current
                            androidx.compose.runtime.CompositionLocalProvider(
                                androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(d.density * scale, d.fontScale),
                            ) { GameBoard(g, vm, ui) }
                        }
                        Column(Modifier.weight(1f).padding(start = 8.dp)) {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text("Table chat", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f).padding(12.dp))
                                WatchersButton(watchers)
                            }
                            ChatPanel(ui.tableChat, vm::sendTableChat, me = ui.me?.username)
                        }
                    }
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        androidx.compose.material3.TabRow(selectedTabIndex = tab, modifier = Modifier.weight(1f)) {
                            androidx.compose.material3.Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Game") })
                            androidx.compose.material3.Tab(
                                selected = tab == 1,
                                onClick = { tab = 1 },
                                text = { Text(if (unread > 0) "Chat ($unread)" else "Chat") },
                            )
                        }
                        WatchersButton(watchers)
                    }
                    if (tab == 0) GameBoard(g, vm, ui) else ChatPanel(ui.tableChat, vm::sendTableChat, me = ui.me?.username)
                }
            }
        }
    }
}
/** An eye with the number of people watching; tap to see who. Nothing is drawn when nobody is. */
@Composable
private fun WatchersButton(names: List<String>) {
    if (names.isEmpty()) return
    var open by remember { mutableStateOf(false) }
    val tint = MaterialTheme.colorScheme.onBackground
    Row(
        Modifier.clickable { open = true }.padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        androidx.compose.foundation.Canvas(Modifier.size(width = 22.dp, height = 14.dp)) {
            val stroke = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.6.dp.toPx())
            drawOval(tint, style = stroke)
            drawCircle(tint, radius = size.height * 0.22f)
        }
        Text("${names.size}", style = MaterialTheme.typography.labelLarge)
    }
    if (open) {
        AlertDialog(
            onDismissRequest = { open = false },
            title = { Text("Watching (${names.size})") },
            text = { Column { names.forEach { Text(it) } } },
            confirmButton = { TextButton(onClick = { open = false }) { Text("Close") } },
        )
    }
}

@Composable
private fun GameBoard(live: GameState, vm: LobbyViewModel, ui: LobbyUiState) {
    val moves = ui.moves
    // Review mode: index into live.turnStops, or null for the live game. Everything below draws `g`.
    var reviewIdx by remember { mutableStateOf<Int?>(null) }
    // Moves to animate because the user stepped one turn forward in a replay / review (ids are negative,
    // to tell them from live moves, which the view model numbers upwards).
    var reviewMoves by remember { mutableStateOf<List<live.hanab.client.game.MoveEvent>>(emptyList()) }
    val localIds = remember { longArrayOf(0L) }
    fun goTo(i: Int?) {
        val last = live.turnStops.size - 1
        val stepped = if (last >= 1) live.movesBetweenStops(reviewIdx ?: last, i ?: last) else emptyList()
        reviewMoves = stepped.map { it.copy(id = --localIds[0]) }
        reviewIdx = i
    }
    val g = remember(live, reviewIdx) { reviewIdx?.let { i -> live.turnStops.getOrNull(i)?.let(live::replayTo) } ?: live }

    // Shared replay: the leader's segment drives everyone who is following; the leader's own steps are broadcast.
    val replay = ui.replay
    val amLeader = replay != null && replay.leader.equals(ui.me?.username, ignoreCase = true)
    var follow by remember { mutableStateOf(true) }
    LaunchedEffect(ui.sharedSegment, follow, live.turnStops.size) {
        val seg = ui.sharedSegment
        if (replay != null && !amLeader && follow && seg != null) {
            goTo(if (seg >= live.turnStops.size - 1) null else seg)
        }
    }
    val setReview: (Int?) -> Unit = { i ->
        goTo(i)
        if (replay != null) {
            if (amLeader) vm.sendReplaySegment(i ?: (live.turnStops.size - 1).coerceAtLeast(0)) else follow = false
        }
    }
    // Screen rectangles of cards, stacks and the discard button, used as animation start/end points.
    val bounds = remember { HashMap<String, Rect>() }
    val flights = remember { mutableStateListOf<Flight>() }
    var origin by remember { mutableStateOf(Offset.Zero) }
    // Drawn cards are hidden in the hand until their flight from the deck lands.
    val arriving = remember { mutableStateListOf<Int>() }
    val drawFlights = remember { mutableStateListOf<Triple<Int, Rect, Rect>>() }
    var pulseSuit by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(pulseSuit) { if (pulseSuit != null) { kotlinx.coroutines.delay(380); pulseSuit = null } }
    val pendingDraws = ui.draws.map { it.order }
    // A played card changes its stack only once the flying card has landed: until then the stack keeps
    // showing the old number (held while the flight runs, and for plays not yet picked up below).
    val holdBack = remember { mutableStateMapOf<Int, Int>() }
    // Live moves that arrive while looking at an earlier turn are not animated (the screen shows the past).
    val allMoves = moves.filter { reviewIdx == null } + reviewMoves
    // What was on a stack before the card that just landed on it: one rank down, except where stacks run either way.
    fun before(m: live.hanab.client.game.MoveEvent) = if (g.directional) g.stackRanks.getOrNull(m.suit)?.let { if (it.size >= 2) it[it.size - 2] else 0 } ?: 0 else m.rank - 1
    val pendingHold = allMoves.filter { it.play }.associate { it.suit to before(it) }
    val shownStacks = g.stackTops.mapIndexed { i, v -> holdBack[i] ?: pendingHold[i] ?: v }
    LaunchedEffect(ui.draws) {
        if (ui.draws.isEmpty()) return@LaunchedEffect
        val batch = ui.draws
        vm.consumeDraws(batch.last().id)
        val animate = AppSettings.animSpeed != AnimSpeed.Off && reviewIdx == null
        if (animate) {
            kotlinx.coroutines.delay((350 * AppSettings.animSpeed.factor).toLong()) // after the play/discard flight starts
            for (d in batch) {
                // The new card has to be laid out before we know where it lands.
                var tries = 0
                while (bounds["card:${d.order}"] == null && tries++ < 20) androidx.compose.runtime.withFrameNanos { }
                val deck = bounds["deck"]
                val slot = bounds["card:${d.order}"]
                if (deck != null && slot != null) {
                    arriving += d.order
                    drawFlights += Triple(d.order, deck, slot)
                }
            }
        }
    }
    LaunchedEffect(moves, reviewMoves) {
        // Live moves and replay steps both fly the card from where it was to the stack or the discards.
        allMoves.forEach { m ->
            val from = bounds["card:${m.order}"]
            val to = if (m.play) bounds["stack:${m.suit}"] else bounds["discards"]
            if (AppSettings.animSpeed != AnimSpeed.Off && from != null && to != null) {
                // In a finished game every card is public, so there is nothing to turn over.
                flights += Flight(m, from, to, flip = m.player == g.ourIndex && !g.spectating && !g.revealAll)
                if (m.play) holdBack[m.suit] = before(m)
            }
        }
        if (moves.isNotEmpty()) vm.consumeMoves(moves.last().id)
        if (reviewMoves.isNotEmpty()) reviewMoves = emptyList()
    }
    var selected by remember { mutableStateOf<Int?>(null) }   // own card order awaiting play/discard
    var noteFor by remember { mutableStateOf<Int?>(null) }    // card order whose notes screen is open
    val myName = ui.me?.username
    // The server takes notes only while the game is running: not in a replay, and not once the game has ended.
    val canEditNotes = ui.replay == null && live.phase == Phase.Playing
    fun othersNotes(o: Int) = g.allNotes[o].orEmpty().count { it.text.isNotBlank() && !it.name.equals(myName, ignoreCase = true) }
    // With nothing else to do on a tap (replays, watching), a tap opens the notes screen; long-press always does.
    val tapOpensNotes = g.spectating || g.revealAll
    var showDiscards by remember { mutableStateOf(false) }
    var confirmDeck by remember { mutableStateOf(false) }
    var showTerminate by remember { mutableStateOf(false) }
    val canTerminate = !live.spectating && live.phase == Phase.Playing && ui.replay == null && reviewIdx == null
    // A play/discard that needs a second "are you sure": (order, isPlay, explanation).
    var warn by remember { mutableStateOf<Triple<Int, Boolean, String>?>(null) }
    var infoOn by remember { mutableStateOf(setOf<Int>()) }   // players whose hand shows deduced knowledge
    val partners = g.othersInTurnOrder
    var cluePartner by remember(g.turn) { mutableIntStateOf(partners.first()) }
    var clueChoice by remember(g.turn) { mutableStateOf<ClueChoice?>(null) }
    // Whether the clue panel is open this turn: by itself if the setting is on, otherwise via the Clue button.
    var showClue by remember(g.turn) { mutableStateOf(AppSettings.autoClue) }
    // Height of the clue panel, kept between turns. The hands are sized as if the panel were not there, so
    // opening it never rescales them; the hands area just becomes scrollable.
    var cluePanelPx by remember { mutableFloatStateOf(0f) }
    // The bot's analysis of this game, once run, is a box with arrows under the hands (sized into the hands the same way).
    val analysisDone = ui.analysis as? live.hanab.client.AnalysisUi.Done
    // A replay (of the website's game, shared or solo, or a saved offline game) is a finished game even when it is
    // not drawn as "over".
    val isReplayView = ui.replay != null || ui.replayDatabaseID > 0 || ui.offlineReplayId != null
    val analysisMatches = analysisDone != null && (live.phase == Phase.Over || isReplayView) &&
        analysisDone.request.online == !ui.offline && analysisDone.request.id == analysisGameId(ui)
    var analysisBoxPx by remember { mutableFloatStateOf(0f) }
    var analysisSel by remember(analysisDone) { mutableStateOf<Int?>(null) }
    var mistakesOnly by remember(analysisDone) { mutableStateOf(false) }
    var showAnalysisList by remember { mutableStateOf(false) }
    // Cards the clue being composed would touch (only while composing on our turn).
    val highlighted: Set<Int> = if (!g.ourTurn) emptySet() else clueChoice?.let { c ->
        g.hands[cluePartner].filter { o ->
            val card = g.cards[o] ?: return@filter false
            when (c) {
                is ClueChoice.Color -> g.touchesColor(card.suit, card.rank, c.color)
                is ClueChoice.Rank -> g.touchesRank(card.suit, card.rank, c.rank)
            }
        }.toSet()
    } ?: emptySet()
    fun toggleInfo(p: Int) { infoOn = if (p in infoOn) infoOn - p else infoOn + p }

    // Moves are only accepted on our turn: drop a pending play/discard prompt when the turn passes.
    LaunchedEffect(g.ourTurn) { if (!g.ourTurn) { selected = null; warn = null } }

    Box(Modifier.fillMaxSize().onGloballyPositioned { origin = it.positionInRoot() }) {
    Surface(Modifier.fillMaxSize(), color = Color.Transparent, contentColor = MaterialTheme.colorScheme.onBackground) {
        androidx.compose.runtime.CompositionLocalProvider(
            androidx.compose.material3.LocalMinimumInteractiveComponentSize provides androidx.compose.ui.unit.Dp.Unspecified,
        ) {
        Column(Modifier) {
            // Pinned at the top: the stacks, where played cards land, stay on screen while the hands scroll.
            Column(
                Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 4.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Stacks(g, shownStacks, bounds, pulseSuit)
            }
            androidx.compose.foundation.layout.BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val outer = androidx.compose.ui.platform.LocalDensity.current
            val availPx = with(outer) { maxHeight.toPx() } + (if (g.ourTurn && showClue) cluePanelPx else 0f) +
                (if (analysisMatches) analysisBoxPx else 0f)
            // Width: the widest hand (one card is 62dp with its margin, 2dp between cards) must fit.
            val widthScale = (g.othersInTurnOrder.map { g.hands[it].size } + g.hands[g.ourIndex].size)
                .maxOrNull()?.let { n -> if (n > 0) (maxWidth - 12.dp) / (60.dp * n + 2.dp * (n - 1)) else 1f } ?: 1f
            // Height: measured. naturalPx is the hands' height at scale 1, learnt from the last layout.
            var naturalPx by remember { mutableFloatStateOf(0f) }
            // The end-of-game panel sits above the hands, so do not squeeze them then.
            val fitHeight = g.phase != Phase.Over || reviewIdx != null
            val heightScale = if (fitHeight && naturalPx > 0f) (availPx / naturalPx) else 1f
            // The scale snaps down to steps of 4%. Every size is rounded to whole pixels, so the measured height
            // wobbles a little from one scale to the next; without steps, that wobble would nudge the scale,
            // which re-measures, which wobbles again: the board shook continuously with six players.
            val scale = snapHandScale(minOf(widthScale, heightScale))
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            androidx.compose.runtime.CompositionLocalProvider(
                androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(outer.density * scale, outer.fontScale),
            ) {
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 6.dp)
                    .onSizeChanged { size ->
                        val natural = size.height / scale
                        // Only a real change (another hand, a taller header) counts, not rounding noise.
                        if (naturalPx == 0f || kotlin.math.abs(natural - naturalPx) > naturalPx * 0.03f) naturalPx = natural
                    },
                verticalArrangement = Arrangement.spacedBy(1.dp),
            ) {
                g.othersInTurnOrder.forEach { p ->
                    val kn = if (p in infoOn) g.knowledgeOf(p) else null
                    Column(Modifier.turnGlow(g.phase == Phase.Playing && g.currentPlayer == p)) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                g.name(p) + (if (g.currentPlayer == p && g.phase == Phase.Playing) "  (turn)" else "") +
                                    (g.timeOf(p)?.let { "  ·  $it" } ?: ""),
                                style = MaterialTheme.typography.titleSmall,
                                modifier = Modifier.weight(1f),
                            )
                            CharacterTag(g.characters.getOrNull(p))
                            InfoToggle("What they know", p in infoOn) { toggleInfo(p) }
                        }
                        LazyRow(
                            contentPadding = PaddingValues(top = RAISE, bottom = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(2.dp),
                            userScrollEnabled = false,
                        ) {
                            items(g.hands[p], key = { it }) { o ->
                                g.cards[o]?.let { c ->
                                    val hl = o in highlighted
                                    Box(
                                        Modifier
                                            .animateItem(fadeOutSpec = null)
                                            .alpha(if (o in arriving || o in pendingDraws) 0f else 1f)
                                            .raiseIfClued(c.clued)
                                            .background(if (hl) Color(0xFFFFEB3B) else Color.Transparent, RoundedCornerShape(11.dp))
                                            .padding(2.dp)
                                            .onGloballyPositioned { bounds["card:$o"] = it.boundsInRoot() }
                                            .combinedClickable(onClick = { if (tapOpensNotes) noteFor = o }, onLongClick = { noteFor = o }),
                                    ) {
                                        CardView(g, c, known = true, note = g.notes[o], knowledge = kn?.get(o), others = othersNotes(o))
                                        ClueBubbleFor(g, o)
                                    }
                                }
                            }
                        }
                    }
                }
                // Our own hand always shows what we know / can deduce (a spectator sees real cards instead).
                // After the game every card is public, so (as for partners) knowledge becomes a toggle.
                val myKn = when {
                    // Everything is visible (replay, or watching): the bottom hand gets a toggle like the others.
                    g.revealAll || g.spectating -> if (g.ourIndex in infoOn) g.knowledgeOf(g.ourIndex) else null
                    else -> g.knowledgeOf(g.ourIndex)
                }
                Column(Modifier.turnGlow(g.phase == Phase.Playing && g.currentPlayer == g.ourIndex)) {
                    val me = if (g.spectating) g.name(g.ourIndex) else "You"
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            me + (if (g.ourTurn) "  - your turn" else "") + (g.timeOf(g.ourIndex)?.let { "  ·  $it" } ?: ""),
                            style = MaterialTheme.typography.titleSmall,
                            color = if (g.ourTurn) MaterialTheme.colorScheme.primary else Color.Unspecified,
                            modifier = Modifier.weight(1f),
                        )
                        CharacterTag(g.characters.getOrNull(g.ourIndex))
                        if (g.spectating) InfoToggle("What they know", g.ourIndex in infoOn) { toggleInfo(g.ourIndex) }
                        else if (g.revealAll) InfoToggle("What I knew", g.ourIndex in infoOn) { toggleInfo(g.ourIndex) }
                    }
                    LazyRow(
                        contentPadding = PaddingValues(top = RAISE, bottom = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        userScrollEnabled = false,
                    ) {
                        items(g.hands[g.ourIndex], key = { it }) { o ->
                            g.cards[o]?.let { c ->
                                Box(
                                    Modifier.animateItem(fadeOutSpec = null).alpha(if (o in arriving || o in pendingDraws) 0f else 1f).raiseIfClued(c.clued).onGloballyPositioned { bounds["card:$o"] = it.boundsInRoot() }.combinedClickable(
                                        onClick = { if (g.ourTurn) selected = o else if (tapOpensNotes) noteFor = o },
                                        onLongClick = { noteFor = o },
                                    ),
                                ) {
                                    CardView(g, c, known = g.spectating || g.revealAll, note = g.notes[o], knowledge = myKn?.get(o), others = othersNotes(o))
                                    ClueBubbleFor(g, o)
                                }
                            }
                        }
                    }
                }
            }   // hands column
            }   // density scale
            // Below the hands, reached by scrolling: the result panel, the log, and leaving.
            Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                ui.tables[live.tableID]?.name?.takeIf { it.isNotBlank() }?.let { name ->
                    Text(
                        name,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    )
                }
                if (g.phase == Phase.Over && !g.readOnly) {
                    EndPanel(g, ui, onWatch = { reviewIdx = 0 }, vm = vm)
                } else if (g.phase == Phase.Over || isReplayView) {
                    // A replay: no end panel, but the bot can still go through the game, and bring its analysis back.
                    AnalyzeButton(g, ui, vm)
                }
                Column {
                    g.log.takeLast(5).forEach { Text(it.text, style = MaterialTheme.typography.bodySmall) }
                }
                OutlinedButton(onClick = vm::leaveTable, modifier = Modifier.fillMaxWidth()) {
                    Text(if (g.phase == Phase.Over) "Back to lobby" else "Leave game")
                }
            }
            }   // scroll column
            }   // BoxWithConstraints
            if (g.ourTurn && showClue) {
                Box(Modifier.onSizeChanged { cluePanelPx = it.height.toFloat() }) {
                    CluePanel(g, vm, partners, cluePartner, { cluePartner = it }, clueChoice, { clueChoice = it })
                }
            }
            if (analysisMatches && analysisDone != null) {
                val list = if (mistakesOnly) analysisDone.comments.filter { it.mistake } else analysisDone.comments
                val lastStop = (live.turnStops.size - 1).coerceAtLeast(0)
                // The position before the move a comment is about: turn n is the n-th move, stop n - 1.
                fun stopOf(c: live.hanab.client.AnalysisComment) = (c.turn - 1).coerceIn(0, lastStop)
                val here = reviewIdx ?: lastStop
                // Stepping the replay by hand onto a turn with a comment shows that comment.
                LaunchedEffect(reviewIdx, list) {
                    val i = list.indexOfFirst { stopOf(it) == (reviewIdx ?: lastStop) }
                    if (i >= 0) analysisSel = i
                }
                // Going to a comment moves the replay; the leader of a shared replay moves everyone's (see setReview).
                fun select(i: Int) {
                    analysisSel = i
                    setReview(stopOf(list[i]))
                }
                Box(Modifier.onSizeChanged { analysisBoxPx = it.height.toFloat() }) {
                    AnalysisBox(
                        comment = analysisSel?.let { list.getOrNull(it) },
                        position = analysisSel?.plus(1)?.takeIf { it <= list.size },
                        total = list.size,
                        mistakesOnly = mistakesOnly,
                        canFilter = analysisDone.comments.any { it.mistake } && analysisDone.comments.any { !it.mistake },
                        onMistakesOnly = { mistakesOnly = it; analysisSel = null },
                        onPrev = {
                            if (list.isNotEmpty()) select(list.indexOfLast { stopOf(it) < here }.takeIf { it >= 0 } ?: list.lastIndex)
                        },
                        onNext = {
                            if (list.isNotEmpty()) select(list.indexOfFirst { stopOf(it) > here }.takeIf { it >= 0 } ?: 0)
                        },
                        onList = { showAnalysisList = true },
                        onClose = vm::closeAnalysis,
                    )
                }
            }
            // Pinned at the bottom, where the thumb is: game info on the left, playback buttons on the right.
            Surface(Modifier.padding(horizontal = 12.dp, vertical = 4.dp), tonalElevation = 2.dp, shape = RoundedCornerShape(10.dp)) {
                GameHeader(
                    live, reviewIdx, g, replay, amLeader, follow, { follow = it }, setReview, bounds,
                    showClueButton = g.ourTurn, clueOpen = showClue, onToggleClue = { showClue = !showClue },
                    deckPlayable = g.deckPlayable, onDeckPlay = { confirmDeck = true },
                    // Only players of a game in progress can end it (not spectators, replays or review).
                    onStrikesLongPress = if (canTerminate) ({ showTerminate = true }) else null,
                ) { showDiscards = true }
            }
        }
        }
    }
    flights.toList().forEach { f ->
        key(f.move.id) {
            FlightCard(g, f, origin) {
                flights.remove(f)
                holdBack.remove(f.move.suit)
                if (f.move.play && !f.move.failed) pulseSuit = f.move.suit
            }
        }
    }
    drawFlights.toList().forEach { d ->
        key("draw${d.first}") {
            DrawFlightCard(d.second, d.third, origin) { drawFlights.remove(d); arriving.remove(d.first) }
        }
    }
    }

    if (showDiscards) DiscardDialog(g) { showDiscards = false }
    if (showAnalysisList && analysisDone != null) {
        AnalysisResultDialog(
            analysisDone,
            onAgain = { showAnalysisList = false; vm.analyzeAgain(analysisDone.request) },
            onClose = { showAnalysisList = false },
        )
    }
    if (showTerminate && canTerminate) {
        TerminateDialog(
            voted = ui.terminateVote,
            players = live.numPlayers,
            offline = ui.offline,
            onVote = { vm.voteToTerminate() },
            onTerminate = { vm.terminateNow(); showTerminate = false },
            onDismiss = { showTerminate = false },
        )
    }
    if (confirmDeck) {
        AlertDialog(
            onDismissRequest = { confirmDeck = false },
            title = { Text("Play the last card?") },
            text = { Text("This plays the last card of the deck without seeing it. If it can't be played it is a strike.") },
            confirmButton = {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    androidx.compose.material3.FilledTonalButton(onClick = { confirmDeck = false }, modifier = Modifier.weight(1f)) { Text("Cancel") }
                    Button(onClick = { vm.playDeck(); confirmDeck = false }, enabled = g.deckPlayable, modifier = Modifier.weight(1f)) { Text("Play it") }
                }
            },
        )
    }
    noteFor?.let { order ->
        NotesDialog(g, order, myName, canEditNotes, onSave = { vm.setNote(order, it); noteFor = null }, onDismiss = { noteFor = null })
    }

    warn?.let { (order, isPlay, text) ->
        AlertDialog(
            onDismissRequest = { warn = null },
            title = { Text(if (isPlay) "Play this card?" else "Discard this card?") },
            text = { Text(text) },
            confirmButton = {
                Button(
                    onClick = { if (isPlay) vm.play(order) else vm.discard(order); warn = null },
                    enabled = g.ourTurn,
                ) { Text(if (isPlay) "Play anyway" else "Discard anyway") }
            },
            dismissButton = { TextButton(onClick = { warn = null }) { Text("Cancel") } },
        )
    }

    selected?.let { order ->
        // Cancel, Discard, Play: the safe way out is the first button and the one furthest from Play,
        // where a panicked tap lands. (Discard used to sit in the usual "cancel" spot.)
        AlertDialog(
            onDismissRequest = { selected = null },
            title = { Text("Card in slot ${g.hands[g.ourIndex].indexOf(order) + 1}") },
            confirmButton = {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    androidx.compose.material3.FilledTonalButton(
                        onClick = { selected = null },
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 8.dp),
                    ) { Text("Cancel", maxLines = 1) }
                    OutlinedButton(
                        onClick = {
                            val ids = g.knowledgeOf(g.ourIndex)[order]?.inferred.orEmpty()
                            val reason = g.discardWarning(ids)
                            if (reason != null) warn = Triple(order, false, reason)
                            else vm.discard(order)
                            selected = null
                        },
                        enabled = g.ourTurn && (g.clues < g.maxClues || g.hasInverted),
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 8.dp),
                    ) { Text("Discard", maxLines = 1) }
                    Button(
                        onClick = {
                            val ids = g.knowledgeOf(g.ourIndex)[order]?.inferred.orEmpty()
                            val reason = g.playWarning(ids)
                            if (reason != null) warn = Triple(order, true, reason)
                            else vm.play(order)
                            selected = null
                        },
                        enabled = g.ourTurn,
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 8.dp),
                    ) { Text("Play", maxLines = 1) }
                }
            },
        )
    }}

/** A clue being composed: either a colour (suit index) or a rank. */
sealed interface ClueChoice {
    data class Color(val color: Int) : ClueChoice
    data class Rank(val rank: Int) : ClueChoice
}

/** Bottom bar, shown only on our turn: pick a partner, toggle one clue, and give it if it is valid. */
@Composable
private fun CluePanel(
    g: GameState,
    vm: LobbyViewModel,
    partners: List<Int>,
    partner: Int,
    onPartner: (Int) -> Unit,
    choice: ClueChoice?,
    onChoice: (ClueChoice?) -> Unit,
) {
    if (!g.canClue) {
        Surface(tonalElevation = 3.dp, shadowElevation = 6.dp) {
            Text(
                "You have no clues",
                modifier = Modifier.fillMaxWidth().padding(20.dp),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                style = MaterialTheme.typography.titleMedium,
            )
        }
        return
    }

    val hand = g.hands[partner].mapNotNull { g.cards[it] }
    fun touches(c: ClueChoice) = hand.any { card ->
        when (c) {
            is ClueChoice.Color -> g.touchesColor(card.suit, card.rank, c.color)
            is ClueChoice.Rank -> g.touchesRank(card.suit, card.rank, c.rank)
        }
    }
    // Does [c] touch anything in [p]'s hand? Used to carry a selected clue over to another partner.
    fun touchesIn(p: Int, c: ClueChoice) = g.hands[p].mapNotNull { g.cards[it] }.any { card ->
        when (c) {
            is ClueChoice.Color -> g.touchesColor(card.suit, card.rank, c.color)
            is ClueChoice.Rank -> g.touchesRank(card.suit, card.rank, c.rank)
        }
    }
    // A clue is valid if we have a clue token and it touches at least one card.
    fun allowed(c: ClueChoice) = g.clueAllowed(c is ClueChoice.Color, touches(c))
    val valid = g.canClue && choice?.let { allowed(it) } == true

    Surface(tonalElevation = 3.dp, shadowElevation = 6.dp) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (partners.size > 1) {
                // Equal-width compact chips (names capped at 16 characters). One row is best, so the text shrinks
                // (13sp down to 11sp) to keep it; only if the longest name still does not fit are the partners
                // split over two rows. That depends only on who is at the table, so the panel keeps its height
                // from turn to turn.
                androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxWidth()) {
                    val labels = partners.map { p -> g.name(p).let { if (it.length > 16) it.take(15) + "\u2026" else it } }
                    val chipWidth = (maxWidth - 6.dp * (partners.size - 1)) / partners.size
                    // About 0.54 em per character, plus the chip's padding.
                    val longest = labels.maxOf { it.length }
                    val fitSize = (13 downTo 11).firstOrNull { size -> (0.54f * size * longest).dp + 8.dp <= chipWidth }
                    val oneRow = fitSize != null
                    val fontSize = (fitSize ?: 13).sp
                    val perRow = if (oneRow) partners.size else (partners.size + 1) / 2
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        partners.indices.chunked(perRow).forEach { row ->
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                row.forEach { k ->
                                    val p = partners[k]
                                    PartnerChip(name = labels[k], selected = partner == p, fontSize = fontSize, modifier = Modifier.weight(1f)) {
                                        onPartner(p)
                                        // Keep the chosen clue if it is still valid for the new partner.
                                        if (choice != null && !g.clueAllowed(choice is ClueChoice.Color, touchesIn(p, choice))) onChoice(null)
                                    }
                                }
                                // A short last row keeps the same chip width as the full ones.
                                repeat(perRow - row.size) { Spacer(Modifier.weight(1f)) }
                            }
                        }
                    }
                }
            } else {
                Text("Clue ${g.name(partner)}", style = MaterialTheme.typography.labelLarge)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                g.clueColors.forEachIndexed { ci, name ->
                    val option = ClueChoice.Color(ci)
                    ClueToggle(
                        modifier = Modifier.weight(1f),
                        label = "",
                        pip = name,
                        color = suitColor(name),
                        selected = choice == option,
                        enabled = g.canClue && allowed(option),
                    ) { onChoice(option) }
                }
            }
            if (g.rules.clueRanks.isNotEmpty()) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                g.rules.clueRanks.forEach { r ->
                    val option = ClueChoice.Rank(r)
                    ClueToggle(
                        modifier = Modifier.weight(1f),
                        label = g.rankClueLabel(r),
                        color = Color(0xFF455A64),
                        selected = choice == option,
                        enabled = g.canClue && allowed(option),
                    ) { onChoice(option) }
                }
            }
            Button(
                onClick = {
                    when (val c = choice) {
                        is ClueChoice.Color -> vm.colorClue(partner, c.color)
                        is ClueChoice.Rank -> vm.rankClue(partner, c.rank)
                        null -> {}
                    }
                    onChoice(null)
                },
                enabled = valid,
                modifier = Modifier.fillMaxWidth(),
            ) {
                val touchedCount = choice?.let { c ->
                    g.hands[partner].count { o ->
                        val card = g.cards[o]
                        card != null && when (c) {
                            is ClueChoice.Color -> g.touchesColor(card.suit, card.rank, c.color)
                            is ClueChoice.Rank -> g.touchesRank(card.suit, card.rank, c.rank)
                        }
                    }
                } ?: 0
                val what = when (choice) {
                    is ClueChoice.Color -> g.clueColors.getOrElse(choice.color) { "?" }
                    is ClueChoice.Rank -> g.rankClueLabel(choice.rank)
                    null -> null
                }
                Text(
                    if (what == null) "Give clue (${g.cluesShown} left)"
                    else "Tell ${g.name(partner)}: $what ($touchedCount card${if (touchedCount == 1) "" else "s"})",
                )
            }
        }
    }
}

@Composable
private fun PartnerChip(name: String, selected: Boolean, fontSize: androidx.compose.ui.unit.TextUnit, modifier: Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(8.dp)
    Box(
        modifier
            .height(34.dp)
            .background(if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent, shape)
            .border(BorderStroke(1.dp, if (selected) Color.Transparent else MaterialTheme.colorScheme.outline), shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            name,
            fontSize = fontSize,
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            color = if (selected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun ClueToggle(
    modifier: Modifier,
    label: String,
    color: Color,
    pip: String? = null,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(8.dp)
    Box(
        modifier
            .height(38.dp)
            .alpha(if (enabled) 1f else 0.25f)
            .background(color, shape)
            .border(if (selected) BorderStroke(3.dp, Color.White) else BorderStroke(1.dp, Color(0x55FFFFFF)), shape)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (pip != null) Pip(pip, 26.dp)
        Text(label, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color.White)
    }
}


@Composable
private fun Stacks(g: GameState, shown: List<Int>, bounds: MutableMap<String, Rect>, pulse: Int?) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        g.suits.forEachIndexed { i, s ->
            Box(
                Modifier.weight(1f).height(36.dp).onGloballyPositioned { bounds["stack:$i"] = it.boundsInRoot() }
                    .graphicsLayer {
                        val s = if (pulse == i) 1.18f else 1f
                        scaleX = s; scaleY = s
                    }.background(suitBrush(s), RoundedCornerShape(6.dp), alpha = if (shown[i] > 0) 1f else 0.25f),
                contentAlignment = Alignment.Center,
            ) {
                Pip(s, 16.dp, Modifier.align(Alignment.TopStart).padding(2.dp))
                if (g.rules.sudoku && shown[i] > 0) {
                    // Stacks start anywhere, so the top rank does not say how far along a stack is: the count follows in brackets.
                    val played = g.stackRanks[i].size - if (shown[i] == g.stackTop(i)) 0 else 1
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                        OutlinedText(live.hanab.client.game.cardRankText(shown[i]), 22.sp)
                        OutlinedText("($played)", 12.sp, fontWeight = FontWeight.Normal)
                    }
                } else {
                    OutlinedText(if (shown[i] > 0) live.hanab.client.game.cardRankText(shown[i]) else "", 22.sp)
                }
            }
        }
    }
}

/**
 * [known] = we can see the real identity (other players' hands). Otherwise we show only what
 * the clues have narrowed it down to.
 */
@Composable
private fun CardView(g: GameState, c: Card, known: Boolean, note: String? = null, knowledge: Knowledge? = null, others: Int = 0) {
    if (knowledge != null) {
        KnowledgeCardView(g, c, knowledge, note, others)
        return
    }
    val dead = g.isDead(c)
    val realId = if (known && c.suit >= 0 && c.rank >= 0) setOf(c.suit to c.rank) else emptySet()
    val knownSuit = if (known) c.suit else c.possibleSuits.singleOrNull() ?: -1
    val knownRank = if (known) c.rank else c.possibleRanks.singleOrNull() ?: -1
    val prism = if (knownSuit >= 0 && knownRank >= 1) prismColor(g, knownSuit, knownRank) else null
    val bgBrush: androidx.compose.ui.graphics.Brush = when {
        knownSuit < 0 -> androidx.compose.ui.graphics.SolidColor(Color(0xFF424242))
        prism != null -> androidx.compose.ui.graphics.SolidColor(prism)
        dead -> dullBrush(g.suits[knownSuit])
        else -> suitBrush(g.suits[knownSuit])
    }
    val simplified = AppSettings.simplifiedCards
    val border = if (c.clued) BorderStroke(3.dp, Color.White) else if (simplified) BorderStroke(1.dp, Color(0xFF9E9E9E)) else null
    val shape = RoundedCornerShape(if (simplified) 8.dp else 6.dp)
    Box(
        Modifier.width(56.dp).height(78.dp)
            .then(if (simplified) Modifier.background(bgBrush, shape) else Modifier)
            .then(if (border != null) Modifier.border(border, shape) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        if (simplified) {
            OutlinedText(
                if (knownRank >= 0) live.hanab.client.game.cardRankText(knownRank) else "", 30.sp,
                fill = if (dead) Color(0xFFBDBDBD) else Color.White,
            )
        } else {
            HanabCardFace(if (knownSuit >= 0) g.suits[knownSuit] else null, knownRank, dead, Modifier.fillMaxSize(), wash = prism)
        }
        if (!known && knownRank < 0 && c.possibleRanks.size < g.ranks.size) {
            Text(
                c.possibleRanks.sorted().joinToString(""),
                fontSize = 11.sp, color = if (simplified) Color.White else Color.Black,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 4.dp),
            )
        }
        if (!note.isNullOrBlank()) {
            Text(
                note.take(6), fontSize = 10.sp, color = Color.Black,
                modifier = Modifier.align(Alignment.TopStart).padding(2.dp)
                    .background(Color(0xFFFFEB3B), RoundedCornerShape(3.dp)).padding(horizontal = 2.dp),
            )
        }
        OthersNotesBadge(others, Modifier.align(Alignment.BottomEnd))
        if (simplified && knownSuit >= 0) Pip(g.suits[knownSuit], 16.dp, Modifier.align(Alignment.TopCenter).padding(top = 4.dp))
        CardBadges(g.allCritical(realId), g.allDead(realId), Modifier.align(Alignment.BottomStart))
        if (known && g.isPlayable(c)) {
            Box(Modifier.align(Alignment.TopEnd).padding(4.dp).size(8.dp).background(Color.White, RoundedCornerShape(4.dp)))
        }
    }
}

@Composable
private fun DiscardDialog(g: GameState, onDismiss: () -> Unit) {
    var tab by remember { mutableIntStateOf(0) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(selected = tab == 0, onClick = { tab = 0 }, label = { Text("Discards (${g.discards.size})") })
                FilterChip(selected = tab == 1, onClick = { tab = 1 }, label = { Text("Cards left") })
            }
        },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (tab == 1) Text(
                    "Big number: copies not yet played or discarded. Small: of those, how many are not in anyone's hand that you can see.",
                    style = MaterialTheme.typography.bodySmall,
                )
                g.suits.forEachIndexed { s, name ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Box(
                            Modifier.width(24.dp).height(if (tab == 0) 34.dp else 48.dp).background(suitBrush(name), RoundedCornerShape(5.dp)),
                            contentAlignment = Alignment.Center,
                        ) { Pip(name, 18.dp) }
                        if (tab == 0) {
                            val ranks = g.discards.mapNotNull { g.cards[it] }.filter { it.suit == s }.map { it.rank }.sorted()
                            if (ranks.isEmpty()) Text("-", style = MaterialTheme.typography.bodySmall)
                            ranks.forEach { r ->
                                Box(
                                    Modifier.width(28.dp).height(34.dp).background(suitBrush(name), RoundedCornerShape(5.dp)),
                                    contentAlignment = Alignment.Center,
                                ) { OutlinedText(live.hanab.client.game.cardRankText(r), 16.sp) }
                            }
                        } else {
                            g.ranks.forEach { r ->
                                val left = g.remaining(s, r)
                                Column(
                                    Modifier.width(40.dp).height(48.dp)
                                        .background(suitBrush(name), RoundedCornerShape(6.dp), alpha = if (left > 0) 1f else 0.25f),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.Center,
                                ) {
                                    OutlinedText("$left", 18.sp)
                                    OutlinedText("${live.hanab.client.game.cardRankText(r)}\u00B7${g.unseen(s, r).coerceAtLeast(0)}", 10.sp, fontWeight = FontWeight.Normal)
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

/** A small bubble with a count, hanging off a card's corner when other people have written notes on it. */
@Composable
private fun OthersNotesBadge(count: Int, modifier: Modifier) {
    if (count <= 0) return
    Box(
        modifier.offset(x = 3.dp, y = 3.dp).height(16.dp).widthIn(min = 16.dp)
            .background(Color(0xFFECEFF1), RoundedCornerShape(8.dp))
            .border(1.dp, Color(0xFF607D8B), RoundedCornerShape(8.dp))
            .padding(horizontal = 4.dp),
        contentAlignment = Alignment.Center,
    ) { Text("$count", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color.Black) }
}

/**
 * Notes on one card: your own note (editable while the game runs) and everyone else's, grouped by
 * person with their name and whether they were a player or a spectator. In a replay or after a game the
 * server accepts no new notes, so the screen is read-only. Notes are not saved per turn: stepping back
 * through earlier turns shows the same notes.
 */
@Composable
private fun NotesDialog(g: GameState, order: Int, me: String?, canEdit: Boolean, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    var text by remember(order) { mutableStateOf(g.notes[order] ?: "") }
    val others = g.allNotes[order].orEmpty().filter { it.text.isNotBlank() && !it.name.equals(me, ignoreCase = true) }
    val mine = g.notes[order].orEmpty()
    val card = g.cards[order]
    val known = card != null && card.suit in g.suits.indices && card.rank in g.ranks
    val holder = g.hands.indexOfFirst { order in it }
    val where = when {
        holder >= 0 -> "${g.name(holder)}'s hand, slot ${g.hands[holder].indexOf(order) + 1}"
        order in g.discards -> "In the discard pile"
        else -> "Played"
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (known && card != null) {
                    val suit = g.suits[card.suit]
                    Box(
                        Modifier.width(34.dp).height(46.dp).background(suitBrush(suit), RoundedCornerShape(6.dp))
                            .border(1.5.dp, Color.White, RoundedCornerShape(6.dp)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Pip(suit, 14.dp, Modifier.align(Alignment.TopCenter).padding(top = 2.dp))
                        OutlinedText(live.hanab.client.game.cardRankText(card.rank), 20.sp, Modifier.align(Alignment.BottomCenter))
                    }
                }
                Column {
                    Text(if (known && card != null) g.cardName(card.suit, card.rank) else "Card", style = MaterialTheme.typography.titleMedium)
                    Text(where, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (canEdit) {
                    Text("Your note", style = MaterialTheme.typography.labelLarge)
                    OutlinedTextField(
                        value = text, onValueChange = { text = it },
                        placeholder = { Text("e.g. r5, chop, f") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else if (mine.isNotBlank()) {
                    Text("Your note", style = MaterialTheme.typography.labelLarge)
                    Text(mine)
                }
                others.forEach { n ->
                    Column {
                        Text(
                            n.name + if (n.isSpectator) "  (spectator)" else "  (player)",
                            style = MaterialTheme.typography.labelLarge,
                        )
                        Text(n.text)
                    }
                }
                if (others.isEmpty() && !canEdit && mine.isBlank()) Text("No notes on this card.")
                if (!canEdit) {
                    Text(
                        "Notes can't be added in a replay or after the game has ended.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = {
            if (canEdit) Button(onClick = { onSave(text) }) { Text("Save") }
            else TextButton(onClick = onDismiss) { Text("Close") }
        },
        dismissButton = { if (canEdit) TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
@Composable
private fun InfoToggle(label: String, on: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = on,
        onClick = onClick,
        label = { Text(label, fontSize = 10.sp) },
        modifier = Modifier.height(22.dp),
    )
}

/** Draws a red X over the content when [on]; used for possibilities ruled out by deduction. */
private fun Modifier.xOut(on: Boolean): Modifier = if (!on) this else this.drawWithContent {
    drawContent()
    val stroke = 2.dp.toPx()
    val red = Color(0xFFFF1744)
    drawLine(red, Offset(0f, 0f), Offset(size.width, size.height), stroke, androidx.compose.ui.graphics.StrokeCap.Round)
    drawLine(red, Offset(size.width, 0f), Offset(0f, size.height), stroke, androidx.compose.ui.graphics.StrokeCap.Round)
}

/**
 * Card face showing what is known about the card instead of its identity.
 *  - Middle: the possible suits as symbols on a ring (one large symbol once the suit is certain).
 *  - Bottom: the possible ranks (one large number once the rank is certain).
 *  - A red X over a symbol or number means only deduction (visible cards) rules it out, not a clue.
 *  - A suit or rank is shown as certain only when a clue settled it; deduction never collapses the card.
 *  - Top-left: note tag. Top-right: critical "!" or discardable bin.
 */
@Composable
private fun KnowledgeCardView(g: GameState, c: Card, k: Knowledge, note: String?, others: Int = 0) {
    // Only what the CLUES settled is shown as a certain suit / rank. A suit or rank that is merely deduced
    // keeps all its candidates on show with the ruled-out ones crossed out, so a partner can tell what you
    // were told apart from what you worked out.
    val solvedSuit = k.directSuits.singleOrNull()
    val solvedRank = k.directRanks.singleOrNull()
    val discardable = g.allDead(k.inferred)
    val bg: androidx.compose.ui.graphics.Brush = when {
        solvedSuit == null -> androidx.compose.ui.graphics.SolidColor(Color(0xFF37474F))
        discardable -> dullBrush(g.suits[solvedSuit])
        else -> suitBrush(g.suits[solvedSuit])
    }
    val shape = RoundedCornerShape(8.dp)
    val border = when {
        c.clued -> BorderStroke(3.dp, Color.White)
        else -> BorderStroke(1.dp, Color(0xFF9E9E9E))
    }
    Box(Modifier.width(56.dp).height(78.dp).background(bg, shape).border(border, shape)) {
        // --- suits: a ring of symbols in the middle of the card
        val cx = 28f
        val cy = 34f
        if (solvedSuit != null) {
            val s = 32f
            Pip(g.suits[solvedSuit], s.dp, Modifier.offset((cx - s / 2).dp, (cy - s / 2).dp))
        } else {
            val suits = k.directSuits.toList()
            val n = suits.size
            val size = when {
                n <= 3 -> 21f
                n <= 5 -> 19f
                else -> 17f
            }
            val radius = when (n) { 1 -> 0f; 2 -> 13f; 3 -> 14f; 4 -> 15f; 5 -> 16f; else -> 17f }
            suits.forEachIndexed { i, s ->
                // 2 suits sit left/right; 3+ start at the top and go clockwise.
                val angle = when {
                    n == 2 -> if (i == 0) Math.PI else 0.0
                    else -> -Math.PI / 2 + 2 * Math.PI * i / n
                }
                val x = cx + radius * kotlin.math.cos(angle).toFloat() - size / 2
                val y = cy + radius * kotlin.math.sin(angle).toFloat() - size / 2
                val circle = androidx.compose.foundation.shape.CircleShape
                Box(
                    Modifier.offset(x.dp, y.dp).size(size.dp)
                        .background(suitBrush(g.suits[s]), circle)
                        .border(1.dp, Color.White, circle)
                        .xOut(s !in k.inferredSuits),
                    contentAlignment = Alignment.Center,
                ) { Pip(g.suits[s], (size - 5f).dp) }
            }
        }
        // --- ranks: along the bottom
        if (solvedRank != null) {
            OutlinedText(
                "$solvedRank", 26.sp,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 4.dp),
            )
        } else {
            Row(Modifier.align(Alignment.BottomCenter).padding(bottom = 4.dp)) {
                k.directRanks.forEach { r ->
                    Box(Modifier.width(10.dp).height(18.dp).xOut(r !in k.inferredRanks), contentAlignment = Alignment.Center) {
                        OutlinedText("$r", 13.sp)
                    }
                }
            }
        }
        // --- corners
        CardBadges(g.allCritical(k.inferred), discardable, Modifier.align(Alignment.TopEnd))
        if (!note.isNullOrBlank()) {
            Text(
                note.take(4), fontSize = 9.sp, color = Color.Black,
                modifier = Modifier.align(Alignment.TopStart).padding(2.dp)
                    .background(Color(0xFFFFEB3B), RoundedCornerShape(3.dp)).padding(horizontal = 2.dp),
            )
        }
        OthersNotesBadge(others, Modifier.align(Alignment.BottomEnd))
    }
}
/**
 * Corner markers on a card face:
 *  "!" (amber)   critical: the last copy of a card the stacks still need.
 *  bin (grey)    discardable: it is already played or can never be played.
 */
@Composable
private fun CardBadges(critical: Boolean, discardable: Boolean, modifier: Modifier) {
    if (!critical && !discardable) return
    val shape = androidx.compose.foundation.shape.CircleShape
    Row(modifier.padding(3.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        if (critical) {
            Box(Modifier.size(16.dp).background(Color(0xFFFFB300), shape), contentAlignment = Alignment.Center) {
                Text("!", fontSize = 12.sp, fontWeight = FontWeight.ExtraBold, color = Color.Black)
            }
        }
        if (discardable) {
            Box(Modifier.size(16.dp).background(Color(0xFF9E9E9E), shape), contentAlignment = Alignment.Center) {
                BinIcon(Modifier.size(11.dp))
            }
        }
    }
}
private val RAISE = 6.dp

/** Clued cards sit slightly higher than the rest of the hand, which makes the chop easy to spot. */
@Composable
private fun Modifier.raiseIfClued(clued: Boolean): Modifier {
    val lift by androidx.compose.animation.core.animateDpAsState(if (clued) -RAISE else 0.dp, label = "raise")
    return this.offset(y = lift)
}

/**
 * Bubble under a card that the latest clue touched: a colour dot or a rank number. It is shown while
 * the clue is current: from when it is given until the turn after it ends. Because it is derived
 * from the game state, it also appears when stepping through earlier turns in review.
 *
 * It draws the eye: the bubble grows from nothing with a springy overshoot (starting a moment later
 * for each card to the right, so the eye follows along), then sends out two ripples. It animates every
 * time it enters, in live play and in review alike, and follows the Animation speed setting.
 */
@Composable
private fun androidx.compose.foundation.layout.BoxScope.ClueBubbleFor(g: GameState, order: Int) {
    val mark = g.lastClue ?: return
    if (g.turn > mark.turn + 1 || order !in mark.orders) return

    val speed = AppSettings.animSpeed
    val animate = speed != AnimSpeed.Off
    // Which touched card this is, left to right in the hand: later cards start slightly later.
    val position = g.hands.getOrNull(mark.target)?.filter { it in mark.orders }?.indexOf(order)?.coerceAtLeast(0) ?: 0
    val grow = remember(mark.turn, mark.giver, order) { Animatable(if (animate) 0f else 1f) }
    val ripple = remember(mark.turn, mark.giver, order) { Animatable(1f) }
    LaunchedEffect(mark.turn, mark.giver, order, animate) {
        if (!animate) { grow.snapTo(1f); return@LaunchedEffect }
        grow.snapTo(0f)
        kotlinx.coroutines.delay((position * 120 * speed.factor).toLong())
        grow.animateTo(
            1f,
            androidx.compose.animation.core.spring(dampingRatio = 0.4f, stiffness = 170f),
        )
        repeat(2) {
            ripple.snapTo(0f)
            ripple.animateTo(1f, androidx.compose.animation.core.tween((650 * speed.factor).toInt().coerceAtLeast(1)))
        }
    }

    val shape = androidx.compose.foundation.shape.CircleShape
    Box(
        Modifier
            .align(Alignment.BottomCenter)
            .offset(y = 4.dp)
            .size(20.dp)
            .graphicsLayer { scaleX = grow.value; scaleY = grow.value }
            .drawBehind {
                // An expanding, fading ring around the bubble.
                val p = ripple.value
                if (p < 1f) {
                    drawCircle(
                        Color.White.copy(alpha = (1f - p) * 0.7f),
                        radius = size.minDimension / 2f * (1f + p * 1.1f),
                        style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.dp.toPx()),
                    )
                }
            }
            .background(
                if (mark.color != null && mark.sound == null) suitBrush(mark.color) else androidx.compose.ui.graphics.SolidColor(Color(0xFF455A64)),
                shape,
            )
            .border(2.dp, Color.White, shape),
        contentAlignment = Alignment.Center,
    ) {
        if (mark.sound != null) {
            Text(mark.sound, fontSize = 7.sp, fontWeight = FontWeight.ExtraBold, color = Color.White, maxLines = 1)
        } else if (mark.rank != null) {
            val label = mark.rankLabel ?: "${mark.rank}"
            Text(label, fontSize = if (label.length > 1) 9.sp else 14.sp, fontWeight = FontWeight.ExtraBold, color = Color.White)
        }
        if (mark.color != null && mark.sound == null) Pip(mark.color, 14.dp)
    }
}/** A small black waste bin, drawn directly so its colour is not decided by the emoji font. */
@Composable
private fun BinIcon(modifier: Modifier, color: Color = Color.Black) {
    androidx.compose.foundation.Canvas(modifier) {
        val w = size.width
        val h = size.height
        val black = color
        // lid and handle
        drawRect(black, topLeft = Offset(0f, h * 0.18f), size = androidx.compose.ui.geometry.Size(w, h * 0.12f))
        drawRect(black, topLeft = Offset(w * 0.35f, 0f), size = androidx.compose.ui.geometry.Size(w * 0.3f, h * 0.18f))
        // body, slightly tapered
        val body = androidx.compose.ui.graphics.Path().apply {
            moveTo(w * 0.12f, h * 0.36f)
            lineTo(w * 0.88f, h * 0.36f)
            lineTo(w * 0.78f, h)
            lineTo(w * 0.22f, h)
            close()
        }
        drawPath(body, black)
    }
}

/**
 * "Analyze": the bot, on this phone, goes through the finished game. It knows which game it is for a game of the
 * website (its replay id, also when watching it as a replay) and for an offline game (the one just kept, or the
 * saved one being replayed). Nothing is shown when the game cannot be told, or where the library is not packaged.
 */
@Composable
private fun AnalyzeButton(g: GameState, ui: LobbyUiState, vm: LobbyViewModel) {
    val id = analysisGameId(ui)
    if (id == null || !live.hanab.client.NativeOffline.available || !live.hanab.client.game.Variants.canAnalyze(g.variantName)) return
    val context = androidx.compose.ui.platform.LocalContext.current
    // A kept analysis opens straight away, so the button reads "Analysis" rather than "Analyze".
    val kept = remember(id, ui.analysis) { live.hanab.client.AnalysisStore.exists(context, !ui.offline, id) }
    OutlinedButton(onClick = {
        vm.askAnalysis(
            live.hanab.client.AnalysisRequest(
                "${g.variantName} · ${g.playerNames.joinToString(", ")}", g.variantName,
                live.hanab.client.BotStore.convention, online = !ui.offline, id = id,
            ),
        )
    }) { Text(if (kept) "Analysis" else "Analyze") }
}

/** Shown when the game is over: result, share link, play again, and a way into the shared replay. */
@Composable
private fun EndPanel(g: GameState, ui: LobbyUiState, onWatch: () -> Unit, vm: LobbyViewModel) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val replay = ui.replay
    val link = replay?.takeIf { it.databaseID > 0 }?.let { "https://hanab.live/shared-replay/${it.databaseID}" }
    Surface(tonalElevation = 4.dp, shape = RoundedCornerShape(12.dp)) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(g.endText, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text("Score ${g.finalScore} / ${g.scoreDenominator}", style = MaterialTheme.typography.titleMedium)
            Text(g.playerNames.joinToString(", "), style = MaterialTheme.typography.bodySmall)
            if (g.playerTimes.isNotEmpty()) {
                // What the server measured for each player (the website shows it one step on in a replay).
                Text(
                    (if (g.options.timed) "Time left: " else "Time taken: ") +
                        g.playerNames.zip(g.playerTimes) { n, t -> "$n ${g.clockOf(t)}" }.joinToString(", ") +
                        (if (g.durationMs > 0) "  ·  game ${formatClock(g.durationMs)}" else ""),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            ui.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onWatch) { Text("Watch replay") }
                if (!g.spectating) OutlinedButton(onClick = vm::restartTable) { Text("Play again") }
            }
            AnalyzeButton(g, ui, vm)
            if (link != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = {
                        val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(android.content.Intent.EXTRA_TEXT, link)
                        }
                        context.startActivity(android.content.Intent.createChooser(send, "Share replay"))
                    }) { Text("Share link") }
                    OutlinedButton(onClick = {
                        context.startActivity(
                            android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(link)),
                        )
                    }) { Text("Open on web") }
                }
            }
        }
    }
}


/**
 * Lights up whoever's turn it is: an amber outline and a faint amber wash behind their hand.
 * The padding is always applied so the layout does not jump when the turn moves.
 */
@Composable
private fun Modifier.turnGlow(active: Boolean): Modifier {
    val amber = Color(0xFFFFB300)
    val line by androidx.compose.animation.animateColorAsState(if (active) amber else Color.Transparent, label = "turnLine")
    val wash by androidx.compose.animation.animateColorAsState(if (active) amber.copy(alpha = 0.14f) else Color.Transparent, label = "turnWash")
    val shape = RoundedCornerShape(12.dp)
    return this.background(wash, shape).border(1.5.dp, line, shape).padding(horizontal = 5.dp, vertical = 1.dp)
}

/**
 * Everything about the game state in one compact box, pinned to the bottom of the screen:
 *
 *     turn info (live / reviewing + last move)
 *     clues  strikes  deck  score  discards            [<] [>]
 *
 * [<] steps back one turn and [>] forward one. Long-press [<] to jump to the first turn and [>] to jump
 * back to the live game, so there is no separate "live" button.
 */
@Composable
private fun GameHeader(
    live: GameState,
    idx: Int?,
    shown: GameState,
    replay: live.hanab.client.ReplayInfo?,
    amLeader: Boolean,
    follow: Boolean,
    onFollow: (Boolean) -> Unit,
    onChange: (Int?) -> Unit,
    bounds: MutableMap<String, Rect>,
    showClueButton: Boolean,
    clueOpen: Boolean,
    onToggleClue: () -> Unit,
    deckPlayable: Boolean,
    onDeckPlay: () -> Unit,
    onStrikesLongPress: (() -> Unit)?,
    onDiscards: () -> Unit,
) {
    val g = shown
    val n = live.turnStops.size
    val amber = Color(0xFFFFB300)
    Column(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        // ---- turn info
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            val text = when {
                idx != null -> "Turn ${g.turn + 1}/${live.turn + 1} \u00B7 " + (g.log.lastOrNull()?.text ?: "start")
                g.phase == Phase.Over -> "Game over"
                g.ourTurn -> "Turn ${g.turn + 1} \u00B7 your turn"
                else -> "Turn ${g.turn + 1} \u00B7 ${g.name(g.currentPlayer)}"
            }
            Text(
                (if (replay != null && idx == null) "Replay \u00B7 " else "") + text,
                style = MaterialTheme.typography.labelMedium,
                color = if (idx != null) amber else Color.Unspecified,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (showClueButton) {
                // Opens / closes the clue panel above (it can open by itself: see Settings).
                androidx.compose.material3.FilledTonalButton(
                    onClick = onToggleClue,
                    modifier = Modifier.height(28.dp),
                    contentPadding = PaddingValues(horizontal = 14.dp),
                ) { Text(if (clueOpen) "Clue \u25BC" else "Clue \u25B2", fontSize = 13.sp) }
            }
            if (replay != null && !amLeader) {
                Text(
                    if (follow) "following \u2713" else "follow",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable { onFollow(!follow) }.padding(horizontal = 6.dp),
                )
            }
        }
        // ---- widgets on the left, playback buttons on the right
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                // clues: a token with the number
                Box(
                    Modifier.size(24.dp).background(if (!g.canClue) Color(0xFFD32F2F) else Color(0xFF1976D2), androidx.compose.foundation.shape.CircleShape),
                    contentAlignment = Alignment.Center,
                ) { Text(g.cluesShown, fontSize = if (g.cluesShown.length > 2) 10.sp else 13.sp, fontWeight = FontWeight.Bold, color = Color.White) }
                // strikes: three boxes, filled when taken. Long-press opens the terminate / vote form.
                val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
                Row(
                    Modifier
                        .then(
                            if (onStrikesLongPress != null) Modifier.combinedClickable(
                                onClick = {},
                                onLongClick = {
                                    haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                                    onStrikesLongPress()
                                },
                            ) else Modifier,
                        )
                        .padding(horizontal = 6.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    repeat(3) { i ->
                        Box(
                            Modifier.size(14.dp)
                                .background(if (i < g.strikes && !g.hidesScore) Color(0xFFD32F2F) else Color.Transparent, RoundedCornerShape(3.dp))
                                .border(1.5.dp, if (i < g.strikes && !g.hidesScore) Color(0xFFD32F2F) else Color(0xFF9E9E9E), RoundedCornerShape(3.dp)),
                        )
                    }
                }
                // deck: a little card with the count
                Box(
                    Modifier.width(22.dp).height(28.dp)
                        .onGloballyPositioned { bounds["deck"] = it.boundsInRoot() }
                        .background(Color(0xFF455A64), RoundedCornerShape(4.dp))
                        // A gold border says the last card can be played blind (tap the deck to do it).
                        .border(if (deckPlayable) 2.5.dp else 1.5.dp, if (deckPlayable) Color(0xFFFFC107) else Color(0xFFB0BEC5), RoundedCornerShape(4.dp))
                        .clickable(enabled = deckPlayable, onClick = onDeckPlay),
                    contentAlignment = Alignment.Center,
                ) { Text("${g.deckSize}", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.White) }
                // Throw It in a Hole: how many cards went in the hole, not the score.
                Text(
                    if (g.hidesScore) "${g.hole.size} in hole" else "${g.finalScore}/${g.scoreDenominator}",
                    fontSize = if (g.hidesScore) 11.sp else 14.sp, fontWeight = FontWeight.Bold,
                )
                // discard pile: a bin with the count
                OutlinedButton(
                    onClick = onDiscards,
                    modifier = Modifier.height(30.dp).onGloballyPositioned { bounds["discards"] = it.boundsInRoot() },
                    contentPadding = PaddingValues(horizontal = 8.dp),
                ) {
                    BinIcon(Modifier.size(13.dp), MaterialTheme.colorScheme.onSurface)
                    Text(" ${g.discards.size}", fontSize = 13.sp)
                }
            }
            Spacer(Modifier.width(10.dp))
            NavButton("\u25C0", enabled = n >= 2 && (idx == null || idx > 0),
                onClick = { onChange(if (idx == null) n - 2 else idx - 1) },
                onLongClick = { onChange(0) })          // first turn
            Spacer(Modifier.width(6.dp))
            NavButton("\u25B6", enabled = idx != null,
                onClick = { onChange(if (idx!! + 1 >= n - 1) null else idx + 1) },
                onLongClick = { onChange(null) })       // back to the live game
        }
    }
}

/** An outlined button that also reacts to a long press (with a small buzz). */
@Composable
private fun NavButton(label: String, enabled: Boolean, onClick: () -> Unit, onLongClick: () -> Unit) {
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    val shape = RoundedCornerShape(10.dp)
    val alpha = if (enabled) 1f else 0.35f
    val ink = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha)
    Box(
        Modifier.height(34.dp).width(48.dp)
            .border(1.dp, ink.copy(alpha = alpha * 0.6f), shape)
            .combinedClickable(
                enabled = enabled,
                onClick = onClick,
                onLongClick = {
                    haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                    onLongClick()
                },
            ),
        contentAlignment = Alignment.Center,
    ) { Text(label, color = ink) }
}


/** A player's detrimental character (an emoji and name); tap it to read its restriction. */
@Composable
private fun CharacterTag(character: live.hanab.client.game.CharacterInfo?) {
    if (character == null) return
    var open by remember { mutableStateOf(false) }
    Text(
        "${character.emoji} ${character.name}",
        style = MaterialTheme.typography.labelMedium,
        color = Color(0xFFFFCA28),
        modifier = Modifier.clickable { open = true }.padding(horizontal = 8.dp, vertical = 4.dp),
    )
    if (open) {
        AlertDialog(
            onDismissRequest = { open = false },
            title = { Text("${character.emoji} ${character.name}") },
            text = { Text(character.text) },
            confirmButton = { TextButton(onClick = { open = false }) { Text("Close") } },
        )
    }
}

/** Shows a refusal or warning from the server (e.g. an illegal clue) over the game until dismissed. */
@Composable
fun ErrorBanner(text: String, onDismiss: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.errorContainer, contentColor = MaterialTheme.colorScheme.onErrorContainer) {
        Row(Modifier.fillMaxWidth().clickable(onClick = onDismiss).padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(text, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            Text("OK", fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 12.dp))
        }
    }
}




/**
 * The form behind a long-press on the strikes: vote to end the game, or end it now. A vote is a toggle
 * and the game ends once half the players have voted (both of them in a 2-player game). Ending it now
 * takes two taps, since it can't be undone, and counts as a loss.
 */
@Composable
private fun TerminateDialog(
    voted: Boolean,
    players: Int,
    offline: Boolean,
    onVote: () -> Unit,
    onTerminate: () -> Unit,
    onDismiss: () -> Unit,
) {
    var sure by remember { mutableStateOf(false) }
    val needed = if (players == 2) "both players" else "at least half of the players"
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("End this game?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (offline) {
                    // Only the bots are with you: there is nobody to vote with.
                    Text("The game ends now and is not kept in your offline stats.", style = MaterialTheme.typography.bodyMedium)
                } else {
                    Text("Vote to terminate: the game ends when $needed have voted. You can take your vote back.", style = MaterialTheme.typography.bodyMedium)
                    Text(if (voted) "You have voted to terminate." else "You have not voted.", style = MaterialTheme.typography.labelLarge)
                    Text("End now: the game ends immediately for everyone and counts as a loss.", style = MaterialTheme.typography.bodyMedium)
                }
            }
        },
        confirmButton = {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                androidx.compose.material3.FilledTonalButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text("Cancel") }
                if (!offline) OutlinedButton(onClick = onVote, modifier = Modifier.fillMaxWidth()) {
                    Text(if (voted) "Withdraw my vote" else "Vote to terminate")
                }
                Button(
                    onClick = { if (sure) onTerminate() else sure = true },
                    modifier = Modifier.fillMaxWidth(),
                    colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                ) { Text(if (sure) "Tap again to end the game" else if (offline) "End the game" else "End the game now") }
            }
        },
    )
}

