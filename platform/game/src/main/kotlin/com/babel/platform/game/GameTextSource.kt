package com.babel.platform.game

import android.graphics.Bitmap
import android.graphics.Color
import android.os.SystemClock
import com.babel.core.common.BabelLogger
import com.babel.core.common.Redact
import com.babel.core.model.CoordinateSpace
import com.babel.core.model.LanguageTag
import com.babel.core.model.Revision
import com.babel.core.model.RowGradient
import com.babel.core.model.SourceIdentity
import com.babel.core.model.SourceStyle
import com.babel.core.model.TextBounds
import com.babel.core.model.TextElement
import com.babel.core.model.TextElementId
import com.babel.core.model.TextOrientation
import com.babel.core.model.TextSourceType
import com.babel.domain.acquisition.TextSourceEvent
import com.babel.domain.game.BackdropEdges
import com.babel.domain.game.CapturePace
import com.babel.domain.game.ChangedBand
import com.babel.domain.game.GameLayout
import com.babel.domain.game.GameLine
import com.babel.domain.game.GameTextFilter
import com.babel.domain.game.GameTextScanner
import com.babel.domain.game.StableLines
import com.babel.domain.game.ThermalLevel
import com.babel.domain.vision.JapaneseScript
import com.babel.platform.screen.ScreenFrameSource
import com.babel.platform.screen.TapForwarder
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await

/**
 * Game text: one window screenshot per call, read whole, settled line by line.
 *
 * Every step is one of the spikes' findings (`docs/milestones/v3.md`):
 *
 * - the picture is of the game's **window**, so Babel's own translations are
 *   not in it and the next line can be read while the last is still covered;
 * - the recogniser is ML Kit's general model — game text is horizontal print,
 *   read at 150–430ms a frame;
 * - what is not worth translating is decided by [GameTextFilter], on the
 *   backdrop measured here from the frame itself;
 * - a line is published only when two frames agree ([StableLines]), which
 *   waits out a typewriter line and an animated backdrop alike.
 *
 * Text is never logged — counts, sizes and times only (`docs/systems/privacy.md`).
 */
@Singleton
class GameTextSource @Inject constructor(
    private val frames: ScreenFrameSource,
    private val logger: BabelLogger,
    private val taps: TapForwarder? = null,
    private val heat: ThermalLevels? = null,
    private val pace: CapturePace = CapturePace(),
) : GameTextScanner {

    override val sourceType: TextSourceType = TextSourceType.GAME

    private val events = MutableSharedFlow<TextSourceEvent>(extraBufferCapacity = 16)

    override fun events(): Flow<TextSourceEvent> = events.asSharedFlow()

    private val lock = Mutex()
    private var lines = StableLines()
    private var otherScript: Boolean? = null
    private var latinVotes = 0.0

    /**
     * Kana seen lately: a game written in Japanese. Its kanji-only names —
     * 「風紀委員会」 — are then Japanese too, and are translated rather than
     * taken for Chinese already in the reader's language.
     */
    private var kanaVotes = 0.0

    /** The game the last reading was of; see [read]. */
    private var lastPackage: String? = null
    private var otherVotes = 0.0
    private var lastSelected: List<GameLine> = emptyList()

    /** A chat's senders seen lately; see [GameLayout.repeatedNames]. */
    private val senders = LinkedHashSet<String>()

    /** A chat's columns seen lately, and when; see [GameLayout.ChatColumn]. */
    private val chatColumns = mutableListOf<Pair<GameLayout.ChatColumn, Long>>()

    /** Where a conversation has been lately, and when; see [GameLayout.conversationPlaces]. */
    private val conversation = mutableListOf<Pair<TextBounds, Long>>()
    private var speakerSpots: List<TextBounds> = emptyList()


    /**
     * Bumped when the screen is cleared. A conversation's lines share one, so
     * the translator's dialogue context carries across them (`PageContext`
     * pages by revision).
     */
    private var session = 0L
    private var published = mutableMapOf<String, TextElementId>()

    /** What each published key was last shown as, for moving it; see [withdrawReplaced]. */
    private val shownElements = mutableMapOf<String, TextElement>()

    /**
     * A published line whose original was painted out, and the panel it was
     * painted from, for [repainted].
     */
    private class Erased(val line: GameLine, val conversational: Boolean, val speaker: Boolean, val panel: Pair<Double, Double>)
    private val erased = mutableMapOf<String, Erased>()

    /** Where each published line was and what its place looked like then, for [replacedSince]. */
    private val publishedLooks = mutableMapOf<String, Pair<TextBounds, IntArray>>()

    private val recognizer by lazy {
        TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
    }

    /** The grid the last read frame was compared on, and what was read from it. */
    private var lastGrid: IntArray? = null
    private var lastLines: List<GameLine> = emptyList()

    /** What each line of [lastLines] looked like, for [keepUnchanged]. */
    private var lastLooks: Map<GameLine, IntArray> = emptyMap()

    override suspend fun read(
        windowId: Int,
        windowBounds: TextBounds,
        packageName: String,
        stillWanted: () -> Boolean,
    ) = coroutineScope {
        // Another game, another start: the script votes and everything
        // remembered are this game's alone. Carried over from Blue Archive,
        // its kana votes had Shattered Pixel Dungeon's own Chinese menu read
        // as Japanese kanji, sent, and drawn back over itself — misread
        // (「排行榜」 as 「排行务」).
        if (packageName != lastPackage) {
            if (lastPackage != null) clear()
            lastPackage = packageName
        }
        // Pictures are taken all along, as fast as the platform allows, and
        // each is looked at at once for translations whose place it no longer
        // shows ([withdrawReplaced]); the recogniser reads the latest one when
        // it is free. Taken while the last was read and read after, a picture
        // was up to 1.6 s old — a list flung under its translations kept them
        // for 2.4 s, and the reading after it was of a list still moving.
        val latest = java.util.concurrent.atomic.AtomicReference<Shot?>(null)
        val fresh = kotlinx.coroutines.channels.Channel<Unit>(kotlinx.coroutines.channels.Channel.CONFLATED)
        val taking = launch {
            var before: Small? = null
            var beforeAt = 0L
            var takenAt = 0L
            activeAt = SystemClock.elapsedRealtime()
            while (stillWanted()) {
                // While the recogniser is busy, not as fast as the platform
                // allows: each picture is 18 MB, and taken three times a
                // second beside a reading, readings slowed by a third. Idle,
                // at once: Fate/Grand Order's band reads take 200 ms, and held
                // to one picture a second its lines settled a second later.
                // And slower once nothing has happened for a while
                // ([CapturePace]); a touch anywhere ends the wait at once, the
                // screen being about to change.
                val hot = heat?.level() ?: ThermalLevel.NONE
                heatLevel = hot
                while (true) {
                    val now = SystemClock.elapsedRealtime()
                    taps?.lastTouchAt?.let { if (it > activeAt) activeAt = it }
                    gapMs = pace.gapMs(now - activeAt, hot)
                    if (now - takenAt >= maxOf(gapMs, if (reading) WATCH_INTERVAL_MS else 0L)) break
                    kotlinx.coroutines.delay(WATCH_POLL_MS)
                }
                takenAt = SystemClock.elapsedRealtime()
                val frame = frames.windowFrame(windowId) ?: continue
                val watchFrom = SystemClock.elapsedRealtime()
                duty.frames.incrementAndGet()
                duty.captureMs.addAndGet(watchFrom - takenAt)
                duty.logIfDue()
                runCatching { withdrawReplaced(frame) }
                val small = smallOf(frame)
                duty.watchMs.addAndGet(SystemClock.elapsedRealtime() - watchFrom)
                newest = small
                // A picture to compare with only if taken a moment before: paced
                // slowly on a still screen, the last one was two seconds old, a
                // tap moved to the next student, and a picture taken mid-slide
                // matched the one from before the tap — the panel stands in the
                // same place on every page — so its skill button's label was
                // taken as still, published where it was mid-slide and drawn
                // out of place.
                val pair = before.takeIf { takenAt - beforeAt <= STILL_PAIR_MS }
                latest.getAndSet(Shot(frame, small, pair))?.frame?.recycle()
                before = small
                beforeAt = takenAt
                fresh.trySend(Unit)
            }
            fresh.close()
        }
        for (signal in fresh) {
            if (!stillWanted()) break
            val shot = latest.getAndSet(null) ?: continue
            reading = true
            val readFrom = System.currentTimeMillis()
            try {
                readOne(
                    shot.frame, windowBounds, packageName,
                    stillOnScreen = { line -> stillBetween(shot.before, shot.small, line.bounds) },
                    stillInPieces = { box, piece -> shot.before?.let { stillPieceByPiece(it, shot.small, box, piece) } },
                    // Taken since: a conversation picked while this one was
                    // read showed the last one's bubbles translated over the
                    // new for a second, published from a picture already gone.
                    unchangedSince = { box -> newest.let { it == null || it === shot.small || stillBetween(shot.small, it, box) } },
                )
            } finally {
                duty.readMs.addAndGet(System.currentTimeMillis() - readFrom)
                reading = false
                shot.frame.recycle()
            }
        }
        taking.cancel()
        runCatching { taking.join() }
        latest.getAndSet(null)?.frame?.recycle()
        Unit
    }

    /**
     * For tests that hold the rules against scenes (`GameCorpusTest`): each
     * frame's reading, what was kept and joined, and what of it is dialogue
     * and speakers. Never set in the app.
     */
    internal var inspect: ((read: List<GameLine>, kept: List<GameLine>, dialogue: Set<GameLine>, speakers: Set<GameLine>, why: Map<GameLine, String>) -> Unit)? = null

    /** Grid cells of moving art, until when; see [readOne]. */
    private val artUntil = LongArray(GRID_COLUMNS * GRID_ROWS)
    private var lastWholeAt = 0L

    /** A line of dialogue was typing out on the last picture read; see [readOne]. */
    private var typedLately = false
    private var typingSince = 0L
    private var readParts: List<IntArray> = emptyList()
    private var lastGridBefore: IntArray? = null
    private var linesBefore: List<GameLine> = emptyList()

    /**
     * What game mode spent in the last minute, logged once a minute: the
     * measure for heat and battery (`docs/milestones/v3.md`, criterion 6).
     * Counts and times only.
     */
    private inner class Duty {
        val frames = java.util.concurrent.atomic.AtomicInteger()
        val reads = Array(KINDS.size) { java.util.concurrent.atomic.AtomicInteger() }
        val ocrMs = java.util.concurrent.atomic.AtomicLong()
        val captureMs = java.util.concurrent.atomic.AtomicLong()
        val watchMs = java.util.concurrent.atomic.AtomicLong()
        val readMs = java.util.concurrent.atomic.AtomicLong()
        val published = java.util.concurrent.atomic.AtomicInteger()
        val withdrawn = java.util.concurrent.atomic.AtomicInteger()
        val moved = java.util.concurrent.atomic.AtomicInteger()
        private val since = java.util.concurrent.atomic.AtomicLong(System.currentTimeMillis())

        suspend fun <T> timed(block: suspend () -> T): T {
            val start = System.currentTimeMillis()
            try {
                return block()
            } finally {
                ocrMs.addAndGet(System.currentTimeMillis() - start)
            }
        }

        fun logIfDue() {
            val now = System.currentTimeMillis()
            val from = since.get()
            if (now - from < DUTY_EVERY_MS || !since.compareAndSet(from, now)) return
            logger.info(
                TAG,
                "duty ${(now - from) / 1000}s: frames ${frames.getAndSet(0)}, reads " +
                    KINDS.indices.joinToString(" ") { "${KINDS[it]}=${reads[it].getAndSet(0)}" } +
                    ", capture ${captureMs.getAndSet(0)}ms, watch ${watchMs.getAndSet(0)}ms, read ${readMs.getAndSet(0)}ms" +
                    " (ocr ${ocrMs.getAndSet(0)}ms), published ${published.getAndSet(0)}, " +
                    "withdrawn ${withdrawn.getAndSet(0)}, moved ${moved.getAndSet(0)}; " +
                    "gap ${gapMs}ms, thermal ${heat?.level()}, battery ${heat?.batteryCelsius()}°C",
            )
        }
    }

    private val duty = Duty()

    /** When anything last happened on the game's screen, in elapsed-realtime ms; see [CapturePace]. */
    @Volatile
    private var activeAt = 0L

    /** The device's thermal level as last read, for [readOne]'s whole readings. */
    @Volatile
    private var heatLevel = ThermalLevel.NONE

    /** The gap between pictures [CapturePace] last set, for the log. */
    @Volatile
    private var gapMs = 0L

    /** The words [readOne] offered for translation lately, and when; see [active]. */
    private val offered = HashMap<String, Long>()

    private fun active() {
        activeAt = SystemClock.elapsedRealtime()
    }

    /** One row of labels' box and weight, as first set; see `elementOf` in [readOne]. */
    private class RowStyle(val top: Int, val bottom: Int, val weight: Float?, val ink: Int?)
    private val rowStyles = ArrayDeque<RowStyle>()

    /** Whether the recogniser is at work on a picture; see [read]. */
    @Volatile
    private var reading = false

    /** The small copy of the newest picture taken, read or not. */
    @Volatile
    private var newest: Small? = null

    /** A picture, a small copy of its brightness, and the small copy of the picture taken before it. */
    private class Shot(val frame: Bitmap, val small: Small, val before: Small?)

    private class Small(val width: Int, val height: Int, val luma: IntArray, val scale: Int)

    private fun smallOf(frame: Bitmap): Small {
        val w = (frame.width / SMALL_SCALE).coerceAtLeast(1)
        val h = (frame.height / SMALL_SCALE).coerceAtLeast(1)
        val row = IntArray(frame.width)
        val out = IntArray(w * h)
        for (y in 0 until h) {
            frame.getPixels(row, 0, frame.width, 0, y * SMALL_SCALE, frame.width, 1)
            for (x in 0 until w) out[y * w + x] = luma(row[x * SMALL_SCALE]).toInt()
        }
        return Small(w, h, out, SMALL_SCALE)
    }

    /** Whether [box] looks the same in two pictures a moment apart. */
    private fun stillBetween(before: Small?, now: Small, box: TextBounds): Boolean {
        if (before == null || before.width != now.width || before.height != now.height) return false
        val l = (box.left / now.scale).coerceIn(0, now.width - 1)
        val r = (box.right / now.scale).coerceIn(l + 1, now.width)
        val t = (box.top / now.scale).coerceIn(0, now.height - 1)
        val b = (box.bottom / now.scale).coerceIn(t + 1, now.height)
        var sum = 0L
        var n = 0
        for (y in t until b) for (x in l until r) {
            val i = y * now.width + x
            sum += abs(before.luma[i] - now.luma[i])
            n++
        }
        return n > 0 && sum.toDouble() / n <= STILL_SMALL_LUMA
    }

    /**
     * Whether every piece of a box, [piece] px wide, stood still between two
     * pictures. A few letters typed at the end of a wide box change too little
     * of it to move the mean over the whole: a dialogue box 2500 px wide read
     * as still while its line was typing out.
     */
    private fun stillPieceByPiece(before: Small, now: Small, box: TextBounds, piece: Int): Boolean {
        val step = piece.coerceAtLeast(now.scale)
        var left = box.left
        while (left < box.right) {
            val right = minOf(box.right, left + step)
            if (!stillBetween(before, now, TextBounds(left, box.top, right, box.bottom, box.space))) return false
            left = right
        }
        return true
    }

    private suspend fun readOne(
        frame: Bitmap,
        windowBounds: TextBounds,
        packageName: String,
        stillOnScreen: (GameLine) -> Boolean = { false },
        stillInPieces: (TextBounds, Int) -> Boolean? = { _, _ -> null },
        unchangedSince: (TextBounds) -> Boolean = { true },
    ) {
        val started = System.currentTimeMillis()
        val grid = gridOf(frame)
        // Moving art where nothing was read lately is left alone between
        // whole readings ([artUntil]): the home screen behind a chat animates
        // in six places, and each was read again, frame after frame, for
        // nothing — reading them took longer than reading the frame whole.
        val nowMs = System.currentTimeMillis()
        val wholeEvery = pace.wholeEveryMs(SystemClock.elapsedRealtime() - activeAt, heatLevel)
        // Whole while a line of dialogue is typing out, too: read in parts, the
        // typed end of 「今どきフィルムカメラ……それもモノクロフィルムは」
        // came from a part that began inside 「モノクロ」, the rest from the
        // reading before, and the line settled as three pieces with 「クロ」
        // untranslated between them.
        val wholeDue = nowMs - lastWholeAt >= wholeEvery || typedLately
        val seen = if (wholeDue) grid else lastGrid?.let { before -> IntArray(grid.size) { if (artUntil[it] > nowMs) before[it] else grid[it] } } ?: grid
        // A whole reading when one is due, changed or not: a reading taken
        // while a screen slid in — the title's box taking in the back button,
        // read as lettering on art — was otherwise offered again on every
        // still frame after, and the page's title was never translated.
        val band = if (wholeDue && lastGrid != null) ChangedBand.Rows(0, GRID_ROWS - 1) else ChangedBand.of(lastGrid, seen, GRID_COLUMNS)
        lastGridBefore = lastGrid
        linesBefore = lastLines
        // A cut or a fade moves the whole picture a long way; an animated
        // character only moves part of it a little. Most frames of a story
        // scene are read whole for the second reason, so extent alone would
        // call every frame a new scene.
        val sceneChanged = lastGrid?.let { before ->
            grid.indices.sumOf { abs(grid[it] - before[it]) }.toDouble() / grid.size > SCENE_CHANGE_LUMA
        } ?: false


        val read: List<GameLine>
        val how: String
        if (band == null) {
            // Nothing moved: the last reading stands, and is offered again so
            // that two frames agreeing still settles a line.
            read = lastLines
            how = "unchanged"
        } else {
            val cellHeight = frame.height.toFloat() / GRID_ROWS
            val (top, bottom) = ChangedBand.widened(
                lastLines,
                ((band.first - BAND_MARGIN_CELLS) * cellHeight).toInt().coerceAtLeast(0),
                ((band.last + 1 + BAND_MARGIN_CELLS) * cellHeight).toInt().coerceAtMost(frame.height),
                margin = cellHeight.toInt() / 2,
                frameHeight = frame.height,
            )
            // Only the parts that changed, when they are a part of the frame:
            // a chat beside a list that did not move, the home screen's art
            // animating at the frame's ends, and the whole frame was read
            // every time. Each part's edges are moved out to blank panel
            // first: cut through an avatar, the avatar's edge was read as part
            // of the name beside it, which then matched nothing.
            val boxes = if (wholeDue) null else changedBoxes(frame, seen)
            val boxArea = boxes?.sumOf { (it[2] - it[0]).toLong() * (it[3] - it[1]) } ?: Long.MAX_VALUE
            val fresh = if (boxes != null && boxes.isNotEmpty() && boxArea < frame.width.toLong() * frame.height * PART_AREA_FRACTION) {
                how = "${boxes.size} parts, ${boxArea * 100 / (frame.width.toLong() * frame.height)}%"
                var merged = lastLines
                for (box in boxes) {
                    val (l, t, r, b) = box.toList()
                    merged = ChangedBand.mergeBox(merged, recognise(frame, t, b, l, r), l, t, r, b)
                }
                readParts = boxes
                frontLayer(frame, merged)
            } else if (bottom - top >= frame.height * WHOLE_FRAME_FRACTION || wholeDue) {
                how = "whole"
                // The art mask outlives a whole reading taken because much
                // changed; only the periodic one, which ignores it, renews it.
                if (wholeDue) {
                    lastWholeAt = nowMs
                    artUntil.fill(0L)
                }
                frontLayer(frame, recognise(frame, 0, frame.height))
            } else {
                how = "band ${bottom - top}px"
                frontLayer(frame, ChangedBand.merge(lastLines, recognise(frame, top, bottom), top, bottom))
            }
            read = keepUnchanged(frame, fresh)
            lastGrid = grid
            lastLines = read
        }
        val readMs = System.currentTimeMillis() - started

        // Speaker labels kept with their dialogue, wrapped lines joined into
        // one utterance (`GameLayout`).
        // Which script the game is written in, remembered for the frames that
        // show too little text to tell (`GameLayout.controls`).
        // Which script the game is written in, voted over recent frames so one
        // frame of a shout in Latin letters does not change it.
        val (latin, other) = GameLayout.scriptLetters(read)
        kanaVotes = kanaVotes * SCRIPT_VOTE_DECAY + read.sumOf { line -> line.text.count { JapaneseScript.isPresentIn(it.toString()) } }
        latinVotes = latinVotes * SCRIPT_VOTE_DECAY + latin
        otherVotes = otherVotes * SCRIPT_VOTE_DECAY + other
        if (latinVotes + otherVotes >= GameLayout.MIN_SCRIPT_LETTERS) otherScript = latinVotes * 2 < latinVotes + otherVotes
        val selected = GameLayout.select(
            read, frame.height, frame.width, otherScript, before = lastSelected, speakerSpots = speakerSpots,
            columns = chatColumns.map { it.first },
        )
        lastSelected = selected
        // Art is what changed and said nothing new: every line read there now
        // was read there before. A line still typing, a scrolled bubble, a
        // new choice — new words — keeps its part read. A whole reading is
        // how anything new over art is found.
        val cw = frame.width.toFloat() / GRID_COLUMNS
        val ch = frame.height.toFloat() / GRID_ROWS
        val parts = readParts.ifEmpty {
            if (how != "whole") emptyList() else ChangedBand.regions(lastGridBefore, grid, GRID_COLUMNS, gap = REGION_GAP_CELLS).map { region ->
                intArrayOf((region.columns.first * cw).toInt(), (region.rows.first * ch).toInt(), ((region.columns.last + 1) * cw).toInt(), ((region.rows.last + 1) * ch).toInt())
            }
        }
        for (part in parts) {
            val inPart = read.filter { it.bounds.right > part[0] && it.bounds.left < part[2] && it.bounds.bottom > part[1] && it.bounds.top < part[3] }
            // The same words in the same place: scrolled, a chat's bubbles say
            // what they said, somewhere else, and are not art. Nor are words
            // read off the art itself, or a label over it read a little
            // differently: a student's waterfall and the translucent panel
            // over it were never left alone, and were read whole 20 times a
            // minute on a screen nobody touched.
            val minSide = minOf(frame.height, frame.width)
            if (inPart.any { line ->
                    GameTextFilter.judge(line, minSide) != GameTextFilter.Verdict.ON_ARTWORK &&
                        linesBefore.none { !otherWords(it.text, line.text) && abs(it.bounds.top - line.bounds.top) <= line.bounds.height / 2 && abs(it.bounds.left - line.bounds.left) <= line.bounds.height }
                }
            ) continue
            for (r in (part[1] / ch).toInt() until ((part[3] / ch).toInt() + 1).coerceAtMost(GRID_ROWS))
                for (c in (part[0] / cw).toInt() until ((part[2] / cw).toInt() + 1).coerceAtMost(GRID_COLUMNS))
                    artUntil[r * GRID_COLUMNS + c] = nowMs + wholeEvery
        }
        readParts = emptyList()

        for (spot in GameLayout.speakerSpots(selected, minOf(frame.height, frame.width))) {
            val others = speakerSpots.filter { abs(it.left - spot.left) > spot.height / 2 || abs(it.top - spot.top) > spot.height / 2 }
            speakerSpots = (listOf(spot) + others).take(MAX_SPEAKER_SPOTS)
        }
        val now = System.currentTimeMillis()
        // Everything remembered of where dialogue stood goes with the scene:
        // a skill's pop-up — its name over its description, a speaker over a
        // line — left its place remembered after it closed, and for a minute
        // the status screen's labels under it were translated as dialogue,
        // then vanished.
        if (sceneChanged) {
            chatColumns.clear()
            conversation.clear()
            senders.clear()
        }
        for (place in GameLayout.conversationPlaces(selected)) {
            conversation.removeAll { (old, _) -> covers(old, place) }
            conversation += place to now
        }
        // A speaker the story keeps hidden — 「？？？」 — is a name too, though
        // it has no letters and is left out as a figure: the line under it
        // is said. 「……待て。」 under it was too short to be a sentence, and
        // with the scene cut every few lines the box was never remembered.
        for (name in read.filter { r -> r.text.trim().length in 2..4 && r.text.trim().all { it in "?？" } }) {
            for (said in selected.filter { GameLayout.speaksAbove(name.bounds, it.bounds) && it.text.any(Char::isLetter) }) {
                conversation.removeAll { (old, _) -> covers(old, said.bounds) }
                conversation += said.bounds to now
            }
        }
        conversation.removeAll { (_, at) -> now - at > CONVERSATION_KEPT_MS }
        while (conversation.size > MAX_CONVERSATION_PLACES) conversation.removeAt(0)
        val places = conversation.map { it.first }
        // A chat's columns: gone with the scene — closed, the chat's place
        // is the home screen's buttons — and otherwise kept a while, so a
        // screen of short replies scrolled into view is still the chat.
        chatColumns.removeAll { (_, at) -> now - at > CONVERSATION_KEPT_MS }
        val columns = chatColumns.map { it.first }
        senders += GameLayout.repeatedNames(selected)
        while (senders.size > MAX_SENDERS) senders.remove(senders.first())
        val panels = object : GameLayout.Panels {
            override fun joined(above: GameLine, below: GameLine) = onePanel(frame, above, below)
            override fun beside(a: GameLine, b: GameLine) = sidePanel(frame, a, b)
            override fun across(line: GameLine, fromX: Int) = onePage(frame, line, fromX)
        }
        val kept = GameLayout.joinWrapped(
            GameLayout.joinRows(selected, GameLayout.dialogue(selected, places, columns, senders, panels)),
            panels,
        )

        val dialogue = GameLayout.dialogue(kept, places, columns, senders, panels)
        val speakers = GameLayout.speakers(kept, places, columns, senders, panels)
        inspect?.let { look ->
            val reasons = HashMap<GameLine, String>()
            GameLayout.dialogue(kept, places, columns, senders, panels, reasons)
            look(read, kept, dialogue, speakers, reasons)
        }
        for (column in GameLayout.chatColumns(dialogue - speakers, GameLayout.underRepeatedNames(kept))) {
            val i = chatColumns.indexOfFirst { it.first.sameAs(column) }
            if (i < 0) chatColumns += column to now else chatColumns[i] = chatColumns[i].first.merged(column) to now
        }
        // Only what reads as dialogue: a line someone speaks, a narration, a
        // choice, an instruction or a pop-up's sentence — and the speaker's
        // name over it. A game's interface is left as it is. Measured on Blue
        // Archive's mission menu and Fate/Grand Order's battles: its labels
        // are short words, most of them kanji a Chinese reader reads anyway,
        // drawn on art the patch cannot paint back cleanly; translated, they
        // came out half-covered (「合同火力演習」 as 「大决战」 and 「演习」 over
        // what was left of it), came and went as the art moved, and stood
        // between the player and the buttons — for words learnt in an hour.
        // And the interface words the reader cannot read, where they can be
        // painted out cleanly and are not behind something: on a plain panel,
        // in full contrast — a pop-up dims what is behind it, and the labels
        // there are not what the player is looking at
        // (`docs/systems/game-text.md`).
        val minSide = minOf(frame.height, frame.width)
        // Labels of one row of one size: a skill card's four labels. A row of
        // three translated beside one over the selected card's glow (backdrop
        // 16–17) left 「EXスキル」 alone in Japanese now and then.
        fun rowMates(line: GameLine) = read.filter { o ->
            o !== line && sameRow(o.bounds, line.bounds) && GameLayout.unreadableLabel(o) &&
                min(o.typeHeight, line.typeHeight).toDouble() / max(o.typeHeight, line.typeHeight) >= SAME_TYPE_RATIO
        }
        // A button of two stacked words, one of them kanji: 「スキル」 over 「成長」.
        // Only the kana half was a label to translate, and 「技能」 stood alone
        // in the corner of a slanted button over the untranslated 「成長」. Such
        // a button is the game's, like 「固有武器」 beside it: left whole.
        fun halfOfButton(line: GameLine) = read.any { o ->
            val stacked = (o.bounds.top - line.bounds.bottom) in -line.typeHeight / 4..line.typeHeight * 2 / 3 ||
                (line.bounds.top - o.bounds.bottom) in -line.typeHeight / 4..line.typeHeight * 2 / 3
            val centred = abs((o.bounds.left + o.bounds.right) / 2 - (line.bounds.left + line.bounds.right) / 2) <= line.typeHeight
            o !== line && stacked && centred && o.text.none(Char::isDigit) &&
                o.text.filter(Char::isLetter).let { l -> l.length >= 2 && l.all { it in '一'..'鿿' } } &&
                min(o.typeHeight, line.typeHeight).toDouble() / max(o.typeHeight, line.typeHeight) >= SAME_TYPE_RATIO
        }
        val labels = read.filter { line ->
            kept.none { it.bounds == line.bounds && it in dialogue } && GameLayout.unreadableLabel(line) && !halfOfButton(line) &&
                GameTextFilter.judge(line, minOf(frame.height, frame.width)).let {
                    it == GameTextFilter.Verdict.TRANSLATE || it == GameTextFilter.Verdict.TOO_SMALL ||
                        // A label already translated on a plain panel is
                        // translated on a translucent one too: 「レベルアップ」 on
                        // one student's page and not on the next, its tab over
                        // her blurred art (backdrop 17–24), read as two kinds of
                        // screen. How busy a backdrop is still a panel (28)
                        // is a first guess from these tabs, not a measurement.
                        (
                            it == GameTextFilter.Verdict.ON_ARTWORK && line.backdropError <= KNOWN_LABEL_MAX_BACKDROP &&
                                (lines.knows(line.text) || rowMates(line).count { m -> GameTextFilter.judge(m, minSide) == GameTextFilter.Verdict.TRANSLATE } >= 2)
                            )
                } &&
                contrastOf(frame, line.bounds) >= ACTIVE_CONTRAST
        }.toSet()
        // Nothing behind a dimmer, dialogue or label: a pop-up darkens the
        // screen under it, and the student's name and role there were read
        // as a sentence and a reply and drawn over the dark — at the pop-up's
        // side, the player not looking at them. Dimmed, nothing in the line
        // is bright: neither its ink nor its panel.
        val shown = (kept.filter { it in dialogue } + labels.filter { l -> kept.none { it.bounds == l.bounds && it in dialogue } })
            .filter { unchangedSince(it.bounds) && !dimmed(frame, it.bounds) }
        // Why each kept line is not offered, as numbers: not said, moved
        // since, or behind a dimmer.
        val withheld = kept.filter { k -> shown.none { it === k } }
        if (withheld.isNotEmpty()) {
            logger.debug(
                TAG,
                "withheld " + withheld.joinToString { k ->
                    val why = when {
                        k !in dialogue && labels.none { it.bounds == k.bounds } -> "unsaid"
                        !unchangedSince(k.bounds) -> "moving"
                        dimmed(frame, k.bounds) -> "dimmed"
                        else -> "?"
                    }
                    "$why@${k.bounds.left},${k.bounds.top} len=${k.text.length}"
                },
            )
        }
        // Words to translate not offered lately: a line typing out, a new
        // one. Not every word read — over a student's waterfall the
        // recogniser read new nonsense off the art every frame — nor a word
        // coming and going: on a translucent panel 「サポーター」 and
        // 「あサポータ」 took turns, and the screen never counted as still.
        val offeredAt = System.currentTimeMillis()
        if (shown.any { it.text !in offered }) active()
        shown.forEach { offered[it.text] = offeredAt }
        offered.values.removeAll { offeredAt - it > OFFERED_KEPT_MS }
        lock.withLock {
            // A line of dialogue is still only if the rest of its box is: a
            // typewriter line's typed part stands still while the letters
            // after it, and the next row, are still appearing. Taken as still
            // by its own box, 「連邦生徒会統括室の室長および、」 was published
            // half typed, and its translation stood over the line as the rest
            // typed out beside and under it.
            // And piece by piece: the few letters typed between two pictures
            // are too little of a box that wide to count. Read in bands while
            // it typed, 「リン行政官……あなた、自分が連邦生徒会の顔だという」
            // settled as three lines — each piece seen twice — and the later
            // ones were read beside the patch of the first.
            // The room is the box's, from where its lines begin: a piece read
            // from the middle of a line has the rest of the line, and the
            // next one, typing out to its left and under it.
            val boxLeft = dialogue.filter { it !in speakers }.minOfOrNull { it.bounds.left }
            fun typingRoom(line: GameLine): TextBounds {
                val b = line.bounds
                val left = minOf(b.left, boxLeft ?: b.left)
                return TextBounds(left, b.top, maxOf(b.right, frame.width - left / 2), (b.bottom + line.typeHeight * 2).coerceAtMost(frame.height), b.space)
            }
            // And a piece of words with hiragana in it taken as a label: a
            // narration's first line, no speaker over it and no dialogue on
            // the screen before, typed out as 「活を送」 and 「いますが」, each
            // a phrase a label may be, and each settled alone. Only letters
            // appearing just after it on its row count, so a menu's label is
            // not held for art moving beside it.
            fun typingAfter(line: GameLine): TextBounds {
                val b = line.bounds
                return TextBounds(b.right, b.top, minOf(frame.width, b.right + line.typeHeight * TYPING_PIECE_OF_HEIGHT), b.bottom, b.space)
            }
            val typing: (GameLine) -> Boolean = { line ->
                if (line in dialogue) {
                    line !in speakers && stillInPieces(typingRoom(line), line.typeHeight * TYPING_PIECE_OF_HEIGHT) == false
                } else {
                    line.text.any { it in 'ぁ'..'ゖ' } && line.bounds.right < frame.width - line.typeHeight &&
                        stillInPieces(typingAfter(line), line.typeHeight * TYPING_PIECE_OF_HEIGHT) == false
                }
            }
            // For as long as typing is held back at most: art moving under
            // a translucent box would otherwise keep every reading whole.
            val typingNow = shown.any(typing)
            if (!typingNow) typingSince = 0L else if (typingSince == 0L) typingSince = nowMs
            typedLately = typingNow && nowMs - typingSince < TYPING_WHOLE_MS
            val stillWhole: (GameLine) -> Boolean = { line ->
                (if (line !in dialogue) stillOnScreen(line) else stillOnScreen(line.copy(bounds = typingRoom(line)))) && !typing(line)
            }
            val update = lines.next(shown, now = System.currentTimeMillis(), sceneChanged = sceneChanged, stillOnScreen = stillWhole, typing = typing, stillInked = { gone ->
                // Still there only if its place still holds ink, and not other
                // words: a new speaker's name read in the tab before it is
                // kept left the last speaker's translation over it for four
                // frames (「雷夫」 over 「Dr.ロマン」).
                inked(frame, gone.bounds) && read.none { otherWords(it.text, gone.text) && overlaps(it.bounds, gone.bounds) } &&
                    // Nor where its pixels are no longer what they were when
                    // it was published: a chat scrolled, other bubbles moved
                    // into its place, nothing read there matched it, and its
                    // translation stood over them for four frames — 3 s.
                    !replacedSince(frame, gone.bounds)
            }, heldStill = { key ->
                publishedLooks[key]?.let { (box, then) -> lookOf(frame, box)?.let { now -> lookDifference(then, now) <= STILL_LOOK_LUMA } } == true
            })
            if (update.keptOver.isNotEmpty()) {
                logger.debug(TAG, "kept " + update.keptOver.entries.joinToString { (k, how) -> "${k.substringAfterLast('@')} $how" })
            }
            update.gone.forEach { erased.remove(it) }
            update.gone.forEach { publishedLooks.remove(it); shownElements.remove(it) }
            val gone = update.gone.mapNotNull { published.remove(it) }
            if (gone.isNotEmpty()) events.emit(TextSourceEvent.Removed(gone))
            duty.withdrawn.addAndGet(gone.size)
            if (gone.isNotEmpty()) active()
            if (update.gone.isNotEmpty()) {
                // Where and why, never what: the key's text is screen text.
                logger.debug(TAG, "withdrawn " + update.gone.joinToString { "${it.substringAfterLast('@')} ${update.why[it]}" })
            }

            val roles = HashMap<TextElementId, Boolean>()
            // A row of labels of one size is set alike: one weight and one ink.
            // Each measured alone, a skill card's 「普通技能」 came out bold
            // beside three regular ones — strokes 0.18 against 0.15, either
            // side of the renderer's step. Not one box: grown to the row's,
            // a menu bar's label patch reached up over the icon above it.
            val rowOf = HashMap<String, Pair<Int, Int>>()
            for (line in shown) {
                if (line in dialogue || !GameLayout.isLabel(line.text)) continue
                val mates = shown.filter { o ->
                    o !in dialogue && GameLayout.isLabel(o.text) && sameRow(o.bounds, line.bounds) &&
                        min(o.typeHeight, line.typeHeight).toDouble() / max(o.typeHeight, line.typeHeight) >= SAME_TYPE_RATIO
                }
                if (mates.size >= 3) rowOf[line.text + "@" + line.bounds.left] = mates.minOf { it.bounds.top } to mates.maxOf { it.bounds.bottom }
            }
            fun elementOf(key: String, read0: GameLine, conversational: Boolean, speaker: Boolean): TextElement {
                // A message in a chat's column starts at the column, not where
                // the recogniser's box did: 「んー」's box began on its bubble's
                // tail, and its translation was set out past the bubble.
                val column = if (speaker) null else chatColumns.firstOrNull { (c, _) -> !c.rightAligned && c.holds(read0) }?.first
                val line = if (column != null && read0.bounds.left < column.edge - read0.typeHeight / 4) {
                    read0.copy(bounds = TextBounds(column.edge - COLUMN_START_SLACK_PX, read0.bounds.top, read0.bounds.right, read0.bounds.bottom, read0.bounds.space))
                } else {
                    read0
                }
                // Every line read, not only those kept: a name left untranslated
                // still has ink an affiliation's patch must not grow into.
                // Remembered per row, so a label settling later on the row, or
                // on the next student's page, is set as the first ones were:
                // measured each time, strokes of one font came out 0.15–0.20,
                // either side of the renderer's step between weights.
                val seen = rowOf[read0.text + "@" + read0.bounds.left]
                // Found again by its place even when the row is not all read on
                // this frame: a label settling alone, its mates held from the
                // frames before, came out bold beside them.
                val centre = seen?.let { (top, bottom) -> (top + bottom) / 2 } ?: ((read0.bounds.top + read0.bounds.bottom) / 2)
                val row = if (seen == null && (conversational || speaker || !GameLayout.isLabel(read0.text))) null else
                    rowStyles.firstOrNull { abs((it.top + it.bottom) / 2 - centre) <= read0.typeHeight / 2 }
                val (inked, look0) = lookOf(frame, line, read, speaker)
                if (seen != null && row == null) {
                    // The box the ink took, not the recogniser's: the patch must
                    // still cover the type.
                    rowStyles.addFirst(RowStyle(inked.bounds.top, inked.bounds.bottom, look0.strokeWeight, look0.foregroundColor))
                    while (rowStyles.size > MAX_ROW_STYLES) rowStyles.removeLast()
                }
                val style = row ?: rowStyles.firstOrNull().takeIf { seen != null }
                // And one ink, where the row's inks are one colour measured
                // twice: 「EXスキル」 on the selected card, under its glow, came
                // out a deeper blue than the three beside it.
                val ink = style?.ink?.takeIf { i -> look0.foregroundColor?.let { colourDistance(it, i) <= ROW_INK_DISTANCE } == true }
                val look = if (style == null) look0 else look0.copy(strokeWeight = style.weight ?: look0.strokeWeight, foregroundColor = ink ?: look0.foregroundColor)
                val siblings = if (conversational || speaker) emptyList() else shown.filter { o ->
                    o !== read0 && o !in dialogue && GameLayout.isLabel(o.text) && sameRow(o.bounds, read0.bounds)
                }.sortedBy { it.bounds.left }.map { it.text }
                val element = toElement(key, inked, look, conversational, windowBounds, packageName, speaker = speaker, row = siblings)
                roles[element.id] = speaker
                lookOf(frame, line.bounds)?.let { publishedLooks[key] = line.bounds to it }
                if (element.style.backdrop != null) {
                    erased[key] = Erased(line, conversational, speaker, panelAround(frame, line.bounds))
                } else {
                    erased.remove(key)
                }
                return element
            }
            // The panel changed under a line that did not: a chat list's
            // selected row is shaded, and when another row was picked the
            // patches kept the old shade — a grey block on the white row
            // left behind, a white one on the row now shaded. Painted again
            // from the panel as it is now; the translation is unchanged.
            val settledKeys = update.settled.mapTo(HashSet()) { it.key }
            val repainted = erased.entries.filter { (key, e) ->
                // Only while the line itself is still read there: scrolled,
                // the panel under its old place changes too, and repainting it
                // there drew patches over the bubbles that had moved in.
                key !in settledKeys && key in published &&
                    // By place, not words: a published line keeps its first reading, and
                    // the row read again under a new shade read a little differently.
                    // Nor by its exact box: under the new shade the recogniser's
                    // box for 「ゆっくり休んでちょうだい」 began 18 px further left.
                    read.any { covers(it.bounds, e.line.bounds) && abs(it.bounds.top - e.line.bounds.top) <= e.line.bounds.height / 2 } &&
                    repainted(e.panel, panelAround(frame, e.line.bounds))
            }.map { (key, e) -> elementOf(key, e.line, e.conversational, e.speaker) }
            if (repainted.isNotEmpty()) {
                logger.debug(TAG, "repainted " + repainted.joinToString { "${it.id.value} weight=${it.style.strokeWeight?.let { w -> "%.2f".format(w) }}" })
            }
            // Every size settling now is seen before any is given out: lines
            // settle together, and the first set its size before the tallest
            // of its kind had been measured.
            update.settled.forEach { steadySize(it.line in speakers, it.line.typeHeight, emOf(it.line)) }
            val upserted = repainted + update.settled.map { settled ->
                val element = elementOf(
                    settled.key,
                    settled.line,
                    // A speaker's name is part of the dialogue but not a line
                    // of it: sent with the story as context, 「玄龍門」 came back
                    // as the last speaker's affiliation and its own, two lines.
                    settled.line in dialogue && settled.line !in speakers,
                    settled.line in speakers,
                )
                published[settled.key] = element.id
                logger.debug(
                    TAG,
                    "settled ${element.id.value} ${Redact.text(settled.line.text)}@${settled.line.bounds.left},${settled.line.bounds.top}," +
                        "${settled.line.bounds.width}x${settled.line.bounds.height} type=${settled.line.typeHeight} " +
                        "${if (element.style.backdrop != null) "erased" else "boxed"} " +
                        "outline=${element.style.outlineColor != null} accent=${element.style.accentColor?.let { "%06x".format(it and 0xFFFFFF) }} ink=${element.style.foregroundColor?.let { "%06x".format(it and 0xFFFFFF) }} centred=${element.style.centred} weight=${element.style.strokeWeight?.let { "%.2f".format(it) }} " +
                        "after ${settled.settledAfterMs}ms",
                )
                element
            }
            // Every line of the batch at its kind's weight as it stands after
            // all of them were measured, not as it stood when each was.
            val weighed = upserted.map { e ->
                val w = roles[e.id]?.let(::weightOf)
                if (w == null || e.style.strokeWeight == w) e else e.copy(style = e.style.copy(strokeWeight = w))
            }
            if (weighed.isNotEmpty()) {
                val keyOf = published.entries.associate { (k, id) -> id to k }
                for (e in weighed) keyOf[e.id]?.let { shownElements[it] = e }
                events.emit(TextSourceEvent.Upserted(weighed))
                duty.published.addAndGet(weighed.size)
                active()
            }
        }
        duty.reads[kindOf(how)].incrementAndGet()
        logger.debug(TAG, "read $how: ${read.size} lines, kept ${kept.size}, in ${readMs}ms")
        // What was left out and why, as numbers: where a line was, how tall,
        // how busy behind it. Never its words.
        val left = read.filter { line -> selected.none { it === line } }
        if (left.isNotEmpty()) {
            logger.debug(
                TAG,
                "left out " + left.joinToString { l ->
                    "${GameTextFilter.judge(l, minOf(frame.height, frame.width))}@${l.bounds.left},${l.bounds.top},${l.bounds.width}x${l.bounds.height}" +
                        " t=${l.typeHeight} e=${"%.0f".format(l.backdropError)}"
                },
            )
        }
    }

    /**
     * Whether [box] still holds ink on this frame: samples far from their
     * row's middle value. A blank panel, plain or graded, has none.
     */
    private fun inked(frame: Bitmap, box: TextBounds): Boolean = inkPerMille(frame, box) >= MIN_INK_PER_MILLE

    /**
     * A line whose pixels have not changed keeps its last reading. Over
     * moving art the recogniser reads the same still line differently from
     * frame to frame, and behind a translucent history screen it took in the
     * dimmed dialogue box or did not — six readings of one entry, each
     * withdrawing the last. Where the line's own pixels are the same as they
     * were, so is its text; only a line whose pixels changed — typed, scrolled,
     * replaced — is read again.
     */
    private fun keepUnchanged(frame: Bitmap, fresh: List<GameLine>): List<GameLine> {
        val looks = HashMap<GameLine, IntArray>()
        val still = lastLines.filter { old ->
            val before = lastLooks[old] ?: return@filter false
            val now = lookOf(frame, old.bounds) ?: return@filter false
            looks[old] = now
            largestDifference(before, now) <= STILL_LOOK_LUMA
        }
        // A fresh reading that reaches past the still line — a typewriter's
        // next characters, outside the box the line had — is a changed line.
        fun within(inner: TextBounds, outer: TextBounds): Boolean {
            val slack = outer.height / 2
            return inner.left >= outer.left - slack && inner.right <= outer.right + slack &&
                inner.top >= outer.top - slack && inner.bottom <= outer.bottom + slack
        }
        val grown = still.filter { old -> fresh.any { covers(old.bounds, it.bounds) && !within(it.bounds, old.bounds) } }.toSet()
        // Or read now as pieces where it was one line: a speaker's row read
        // before its dialogue had typed was not split into name and
        // affiliation, and, its pixels unchanged, stayed one label —
        // 「阿子风纪委员会」 — for the whole line.
        val split = still.filter { old -> fresh.count { within(it.bounds, old.bounds) && it.text != old.text } >= 2 }.toSet()
        val stillKept = still - grown - split
        val kept = fresh.filter { line -> stillKept.none { covers(it.bounds, line.bounds) } } + stillKept
        for (line in kept) if (line !in looks) lookOf(frame, line.bounds)?.let { looks[line] = it }
        lastLooks = looks
        return kept
    }

    /** The line's box as a small grid of mean brightness. */
    private fun lookOf(frame: Bitmap, box: TextBounds): IntArray? {
        val left = box.left.coerceIn(0, frame.width - 1)
        val right = box.right.coerceIn(left + 1, frame.width)
        val top = box.top.coerceIn(0, frame.height - 1)
        val bottom = box.bottom.coerceIn(top + 1, frame.height)
        val w = right - left
        val h = bottom - top
        if (w < LOOK_COLUMNS || h < LOOK_ROWS) return null
        val row = IntArray(w)
        val sums = IntArray(LOOK_COLUMNS * LOOK_ROWS)
        val counts = IntArray(LOOK_COLUMNS * LOOK_ROWS)
        var y = top
        while (y < bottom) {
            frame.getPixels(row, 0, w, left, y, w, 1)
            val r = (y - top) * LOOK_ROWS / h
            var x = 0
            while (x < w) {
                val c = x * LOOK_COLUMNS / w
                sums[r * LOOK_COLUMNS + c] += luma(row[x]).toInt()
                counts[r * LOOK_COLUMNS + c]++
                x += SAMPLE_STEP
            }
            y += SAMPLE_STEP
        }
        return IntArray(sums.size) { if (counts[it] == 0) 0 else sums[it] / counts[it] }
    }

    /** The largest change of any cell: one new glyph in a long line moves the mean by little. */
    /**
     * Whether the place a published line stood in now shows something else:
     * on average far from what it showed then. A recogniser's miss on a still
     * line leaves its pixels alone; moving art behind a translucent box moves
     * a few cells, not the mean of all of them.
     */
    private fun replacedSince(frame: Bitmap, box: TextBounds): Boolean {
        val then = publishedLooks.values.firstOrNull { it.first == box }?.second ?: return false
        val now = lookOf(frame, box) ?: return false
        return replaced(then, now)
    }

    /**
     * Mean difference of the two looks with each one's own mean taken out:
     * what moved, not what was shaded. A chat list's selected row darkens
     * evenly under its words, which stay; a scrolled bubble's words go.
     */
    private fun replaced(then: IntArray, now: IntArray): Boolean = lookDifference(then, now) > REPLACED_LOOK_LUMA

    private fun lookDifference(then: IntArray, now: IntArray): Double {
        if (then.size != now.size) return Double.MAX_VALUE
        val a = then.average()
        val b = now.average()
        return then.indices.sumOf { abs((then[it] - a) - (now[it] - b)) } / then.size
    }

    /**
     * Withdraws, before reading the frame, every published line whose place
     * now shows something else. Read first, a scrolled chat's translations
     * stood over the bubbles that had moved in for as long as the recogniser
     * took — half a second to a second and a half a frame.
     */
    private suspend fun withdrawReplaced(frame: Bitmap) {
        val keys = lock.withLock {
            // Only lines painted out on a plain panel: over moving art a still
            // line's place changes under it, and would be withdrawn and drawn
            // again frame after frame.
            val changed = publishedLooks.filter { (key, look) ->
                val painted = erased[key] ?: return@filter false
                if (painted.line.backdropError > GameTextFilter.MAX_BACKDROP_ERROR) return@filter false
                val now = lookOf(frame, look.first)
                now != null && replaced(look.second, now)
            }.keys.toList()
            if (changed.isEmpty()) return
            // Moved, not gone: dragged slowly, a chat's lines shift a little
            // between pictures, and each was withdrawn — the original showing
            // — and published again a reading later where it had got to:
            // translation, original, translation. Where one shift up or down
            // finds every changed line's look again, they are moved there.
            // The shift found on the topmost line, checked line by line: one
            // replaced or gone off the edge does not stop the rest moving.
            val dy = sharedShift(frame, changed)
            val follows = if (dy == null) emptyList() else changed.filter { foundAt(frame, it, dy) }
            if (dy != null && follows.isNotEmpty()) {
                val moved = follows.mapNotNull { key ->
                    val (box, _) = publishedLooks[key] ?: return@mapNotNull null
                    val shifted = TextBounds(box.left, box.top + dy, box.right, box.bottom + dy, box.space)
                    val element = shownElements[key] ?: return@mapNotNull null
                    val look = lookOf(frame, shifted) ?: return@mapNotNull null
                    publishedLooks[key] = shifted to look
                    erased[key]?.let { e -> erased[key] = Erased(e.line.copy(bounds = shifted), e.conversational, e.speaker, panelAround(frame, shifted)) }
                    lines.move(key, shifted)
                    val b0 = element.bounds
                    val next = element.copy(bounds = TextBounds(b0.left, b0.top + dy, b0.right, b0.bottom + dy, b0.space))
                    shownElements[key] = next
                    next
                }
                movedOut = moved
            }
            val keys = changed - follows.toSet()
            lines.forget(keys)
            keys.forEach { publishedLooks.remove(it); erased.remove(it); shownElements.remove(it) }
            keys.mapNotNull { published.remove(it) }
        }
        if (movedOut.isNotEmpty()) {
            events.emit(TextSourceEvent.Upserted(movedOut))
            duty.moved.addAndGet(movedOut.size)
            active()
            logger.debug(TAG, "moved before reading: ${movedOut.size}")
            movedOut = emptyList()
        }
        val ids = keys
        if (ids.isNotEmpty()) events.emit(TextSourceEvent.Removed(ids))
        duty.withdrawn.addAndGet(ids.size)
        if (ids.isNotEmpty()) active()
        if (ids.isNotEmpty()) logger.debug(TAG, "withdrawn before reading: ${ids.size}")
    }

    private var movedOut: List<TextElement> = emptyList()

    /**
     * One vertical shift that finds every one of [keys]'s looks again, or
     * null. Scrolled, a chat's lines move together; a line replaced by other
     * words matches at no shift.
     */
    private fun sharedShift(frame: Bitmap, keys: List<String>): Int? {
        // The first of a few lines, top down, that is found again: one
        // replaced by other words, or gone past the edge, is found nowhere.
        for (look in keys.mapNotNull { publishedLooks[it] }.sortedBy { it.first.top }.take(SHIFT_TRIES)) {
            shiftOf(frame, look.first, look.second)?.let { return it }
        }
        return null
    }

    private fun shiftOf(frame: Bitmap, b: TextBounds, then: IntArray): Int? {
        fun score(d: Int): Double? {
            val now = lookOf(frame, TextBounds(b.left, b.top + d, b.right, b.bottom + d, b.space)) ?: return null
            return now.indices.sumOf { abs(now[it] - then[it]) }.toDouble() / now.size
        }
        val reach = b.height * MAX_TRACK_LINES
        var best: Int? = null
        var bestScore = Double.MAX_VALUE
        var dy = -reach
        while (dy <= reach) {
            if (dy != 0) score(dy)?.let { if (it < bestScore) { bestScore = it; best = dy } }
            dy += TRACK_STEP_PX
        }
        val coarse = best ?: return null
        var fine = coarse
        var fineScore = bestScore
        for (d in coarse - TRACK_STEP_PX + 1 until coarse + TRACK_STEP_PX) {
            if (d == 0) continue
            score(d)?.let { if (it < fineScore) { fineScore = it; fine = d } }
        }
        return if (fineScore <= STILL_LOOK_LUMA) fine else null
    }

    /** Whether the line at [key] is found again shifted by [dy]. */
    private fun foundAt(frame: Bitmap, key: String, dy: Int): Boolean {
        val (b, then) = publishedLooks[key] ?: return false
        val now = lookOf(frame, TextBounds(b.left, b.top + dy, b.right, b.bottom + dy, b.space)) ?: return false
        return now.indices.sumOf { abs(now[it] - then[it]) }.toDouble() / now.size <= STILL_LOOK_LUMA
    }

    private fun largestDifference(a: IntArray, b: IntArray): Double =
        if (a.size != b.size) Double.MAX_VALUE else a.indices.maxOf { abs(a[it] - b[it]) }.toDouble()

    /**
     * Of lines read over each other, the one in front: a history screen is
     * drawn translucent over the game's dialogue box, the recogniser read
     * now the history and now the dimmed dialogue behind it, and each
     * withdrew the other twice a second. The one behind has a fraction of the
     * other's ink (measured 37–57 per thousand against 264).
     */
    private fun frontLayer(frame: Bitmap, lines: List<GameLine>): List<GameLine> {
        if (lines.size < 2) return lines
        val ink = lines.associateWith { inkPerMille(frame, it.bounds) }
        return lines.filter { line ->
            lines.none { other ->
                other !== line && covers(other.bounds, line.bounds) && ink.getValue(line) * BEHIND_RATIO < ink.getValue(other)
            }
        }
    }

    private fun inkPerMille(frame: Bitmap, box: TextBounds): Int {
        val left = box.left.coerceIn(0, frame.width - 1)
        val right = box.right.coerceIn(left + 1, frame.width)
        val w = right - left
        val row = IntArray(w)
        var ink = 0
        var samples = 0
        var y = box.top.coerceAtLeast(0)
        while (y < box.bottom.coerceAtMost(frame.height)) {
            frame.getPixels(row, 0, w, left, y, w, 1)
            val lumas = DoubleArray((w + SAMPLE_STEP - 1) / SAMPLE_STEP) { luma(row[it * SAMPLE_STEP]) }
            val middle = lumas.sortedArray()[lumas.size / 2]
            for (l in lumas) if (abs(l - middle) >= INK_CONTRAST) ink++
            samples += lumas.size
            y += SAMPLE_STEP
        }
        return if (samples == 0) 0 else ink * 1000 / samples
    }

    /** Mostly different characters: another line, not the same one read a little differently. */
    private fun otherWords(a: String, b: String): Boolean {
        val shared = a.toSet().intersect(b.toSet()).size
        return shared * 2 < minOf(a.toSet().size, b.toSet().size)
    }

    /** A third of the smaller box under the other: layers are offset by half a line. */
    private fun covers(a: TextBounds, b: TextBounds): Boolean {
        val w = minOf(a.right, b.right) - maxOf(a.left, b.left)
        val h = minOf(a.bottom, b.bottom) - maxOf(a.top, b.top)
        return w > 0 && h > 0 && w.toLong() * h * 3 >= minOf(a.width.toLong() * a.height, b.width.toLong() * b.height)
    }

    private fun overlaps(a: TextBounds, b: TextBounds): Boolean {
        val w = minOf(a.right, b.right) - maxOf(a.left, b.left)
        val h = minOf(a.bottom, b.bottom) - maxOf(a.top, b.top)
        return w > 0 && h > 0 && w.toLong() * h * 2 >= minOf(a.width.toLong() * a.height, b.width.toLong() * b.height)
    }

    /** Mean brightness of a [GRID_COLUMNS] x [GRID_ROWS] grid, for [ChangedBand]. */
    private fun gridOf(frame: Bitmap): IntArray {
        val small = Bitmap.createScaledBitmap(frame, GRID_COLUMNS, GRID_ROWS, true)
        val pixels = IntArray(GRID_COLUMNS * GRID_ROWS)
        small.getPixels(pixels, 0, GRID_COLUMNS, 0, 0, GRID_COLUMNS, GRID_ROWS)
        if (small !== frame) small.recycle()
        return IntArray(pixels.size) { luma(pixels[it]).toInt() }
    }

    override suspend fun clear() {
        lock.withLock {
            lines.reset()
            published.clear()
            erased.clear()
            publishedLooks.clear()
            shownElements.clear()
            lines = StableLines()
            lastGrid = null
            lastLines = emptyList()
            lastLooks = emptyMap()
            artUntil.fill(0L)
            lastWholeAt = 0L
            typedLately = false
            typingSince = 0L
            offered.clear()
            rowStyles.clear()
            otherScript = null
            latinVotes = 0.0
            kanaVotes = 0.0
            otherVotes = 0.0
            lastSelected = emptyList()
            conversation.clear()
            chatColumns.clear()
            senders.clear()
            labelWeights.clear()
            lineWeights.clear()
            typeSizes.clear()
            speakerSpots = emptyList()
            session += 1
        }
        events.emit(TextSourceEvent.Cleared(TextSourceType.GAME))
    }

    override suspend fun release() {
        runCatching { recognizer.close() }
    }

    /**
     * Reads rows [top, bottom) of [frame], returning lines in the frame's
     * coordinates. The backdrop is judged on the frame's own pixels around
     * each line, so a band's edge cannot make a panel look like artwork.
     */
    private suspend fun recognise(frame: Bitmap, top: Int, bottom: Int, left: Int = 0, right: Int = frame.width): List<GameLine> = try {
        // Icons are those of the area read now; elsewhere the last stand.
        iconBoxes.removeAll { it.bottom > top && it.top < bottom && it.right > left && it.left < right }
        val whole = top == 0 && bottom == frame.height && left == 0 && right == frame.width
        val part = if (whole) frame else Bitmap.createBitmap(frame, left, top, right - left, bottom - top)
        val result = try {
            duty.timed { recognizer.process(InputImage.fromBitmap(part, 0)).await() }
        } finally {
            if (part !== frame) part.recycle()
        }
        val width = frame.width
        val height = frame.height
        fun onFrame(box: android.graphics.Rect) = TextBounds(box.left + left, box.top + top, box.right + left, box.bottom + top, CoordinateSpace.SCREEN)
        val lines = result.textBlocks.flatMap { it.lines }.flatMap { line ->
            // Never a sentence: a highlighted word in one is another colour,
            // and with it a little taller or shorter (「イベント」 in a guide).
            // Nor one with figures: a skill's description sets its numbers in
            // blue and larger, and 「…攻撃速度を30.6%増加…」 was cut before them.
            line.boundingBox?.takeIf { line.text.none { c -> c in GameLayout.SENTENCE_MARKS || c.isDigit() } }?.let { atTypeChange(frame, onFrame(it)) }
                ?: separated(line).map { (text, box) -> text to onFrame(box) }
        }.mapNotNull { (text, read) ->
            if (text.isBlank()) return@mapNotNull null
            // A title's accent bar read as 「一」 before a kanji: 「一技術革命+」.
            val unbarred = if (text.length >= 3 && text[0] == '一' && colouredMarkInside(frame, read) != null) text.drop(1) else text
            val (unmarked, unmarkedBox) = withoutLeadingIcon(frame, GameLayout.withKatakanaRestored(GameLayout.withoutStrayStroke(unbarred)), onItsPanel(frame, read))
            val (kept, bounds) = withoutTail(frame, unmarked, unmarkedBox)
            if (kept.isBlank()) return@mapNotNull null
            GameLine(kept, bounds, backdropError(frame, width, height, bounds), typeHeight(frame, bounds))
        }
        // Only a speaker's row is cut by colour: a name and its affiliation
        // sit over a line of dialogue. A label over a progress bar has its
        // halves on different colours of bar, and 「ダウンロード中」 came back
        // cut in two and translated as "down" and "loading".
        val around = lines + lastLines
        lines.flatMap { line ->
            if (around.any { it !== line && GameLayout.speaksAbove(line.bounds, it.bounds) }) splitByColour(frame, line) else listOf(line)
        }.flatMap(::atDividers).flatMap { atInkGaps(frame, it) }.flatMap { atIcons(frame, it) }.map { line ->
            // Split pieces too: a name and its badge are cut apart by colour
            // and the badge's 「④」 came back on the name's piece.
            // Not a kana again: the badge before a name is gone by now, and
            // the name's first letter beside its edge read as a number.
            val (text, box) = withoutLeadingIcon(frame, line.text, line.bounds, kana = false)
            if (text == line.text) line else line.copy(text = text, bounds = box)
        }.mapNotNull { line ->
            // Last, on each label alone: two tabs read as one line lost only
            // the first's letter, not the second.
            val whole = withLostStart(frame, line.text, line.bounds) ?: return@mapNotNull null
            if (whole == line.text) line else line.copy(text = whole)
        }.let(::withBracketedParts).let { withSentencePiecesJoined(frame, it) }.let(::withWordPiecesJoined).map { withFiguresReadAgain(frame, it) }.let { withRowGapsRead(frame, it) }
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (failure: Throwable) {
        logger.warn(TAG, "recognition failed", failure)
        emptyList()
    }

    /**
     * [lines] with what stands in a gap of a row of small labels read again,
     * larger. A student's four skill labels are set in small italic type and
     * the recogniser left out the first, 「ノーマルスキル」, on three students of
     * five: the row came out translated three of four. A gap is a label left
     * out when it is twice the row's narrowest gap and more; what is read there
     * at twice the size is added.
     */
    private suspend fun withRowGapsRead(frame: Bitmap, lines: List<GameLine>): List<GameLine> {
        val small = lines.filter { it.typeHeight <= frame.height * SMALL_LABEL_OF_HEIGHT && GameLayout.isLabel(it.text) }
        val added = mutableListOf<GameLine>()
        val done = HashSet<GameLine>()
        for (line in small) {
            if (line in done) continue
            val row = small.filter { sameRow(it.bounds, line.bounds) && abs(it.typeHeight - line.typeHeight) <= line.typeHeight / 4 }.sortedBy { it.bounds.left }
            done += row
            if (row.size < 3) continue
            val gaps = row.zipWithNext { a, b -> b.bounds.left - a.bounds.right }
            val usual = gaps.minOrNull() ?: continue
            for ((i, gap) in gaps.withIndex()) {
                if (gap < usual * 2 + line.typeHeight * 2) continue
                val l = row[i].bounds.right + usual / 2
                val r = row[i + 1].bounds.left - usual / 2
                val t = row.minOf { it.bounds.top }
                val b = row.maxOf { it.bounds.bottom }
                // The ink's own extent, not the gap's: the columns holding ink
                // of the row's labels' colour, so the patch covers the label
                // and nothing of the cards' edges.
                val colour = inkColour(frame, row[i].bounds.left, row[i].bounds.top, row[i].bounds.right, row[i].bounds.bottom) ?: continue
                val column = IntArray(b - t)
                val inked = (l until r).filter { x ->
                    frame.getPixels(column, 0, 1, x, t, 1, b - t)
                    column.count { p ->
                        val dr = Color.red(p) - colour[0]
                        val dg = Color.green(p) - colour[1]
                        val db = Color.blue(p) - colour[2]
                        dr * dr + dg * dg + db * db < LABEL_INK_DISTANCE * LABEL_INK_DISTANCE
                    } >= 2
                }
                // The widest cluster of them: a card's edge is a column or two
                // of the same grey, further off than a glyph's width.
                val clusters = mutableListOf<IntRange>()
                for (x in inked) {
                    val last = clusters.lastOrNull()
                    if (last != null && x - last.last <= line.typeHeight) clusters[clusters.lastIndex] = last.first..x else clusters += x..x
                }
                val label = clusters.maxByOrNull { it.last - it.first } ?: continue
                if (label.last - label.first < line.typeHeight * 2) continue
                val extent = TextBounds(label.first - SIDE_MARGIN_PX, t, label.last + 1 + SIDE_MARGIN_PX, b, line.bounds.space)
                // Nothing read there already, in any size: a menu bar's
                // 「ソーシャル」 stood in its row's gap, taller than its
                // neighbours, and was read again as 「シャル」.
                if (lines.any { overlaps(it.bounds, extent) }) continue
                // Read in the ink's extent alone: the whole gap took in the
                // cards' edges, read as 「1|ノーマルスキル」」.
                val key = "gap@${extent.left / 8},${t / 8},${extent.right / 8}"
                val text = rereads[key]?.firstOrNull()?.text
                    ?: GameLayout.withoutStrayStroke((reread(frame, extent.left, t, extent.right, b, scale = 2) ?: "").replace(" ", ""))
                        .trim { !it.isLetterOrDigit() && it !in "(（)）" }
                        .also { rereads[key] = listOf(GameLine(it, line.bounds, 0.0)) }
                if (text.length < 2 || !GameLayout.isLabel(text)) continue
                added += GameLine(text, extent, backdropError(frame, frame.width, frame.height, extent), line.typeHeight)
            }
        }
        return lines + added
    }

    /**
     * [lines] with the pieces of one sentence the recogniser cut apart joined
     * again: two pieces on a row, of one size of type, with no more than
     * half a glyph between them, one of them a sentence. A skill's
     * description came back as 「…攻撃速度?」 and 「を30.6%増加(20秒間)/」
     * touching end to end; the two were translated apart, the first squeezed
     * into two tiny lines, and the paragraph was no longer joined line to
     * line.
     */
    private suspend fun withSentencePiecesJoined(frame: Bitmap, lines: List<GameLine>): List<GameLine> {
        val out = lines.toMutableList()
        var i = 0
        while (i < out.size) {
            val a = out[i]
            // Figures are set larger than the words around them, and the gap
            // before them can be a whole glyph's: 「攻撃力」 「を567増加/」.
            val b = out.firstOrNull { it !== a && sameRow(it.bounds, a.bounds) && it.bounds.left >= a.bounds.right - a.typeHeight / 4 &&
                it.bounds.left - a.bounds.right <= max(a.typeHeight, it.typeHeight) * 3 / 2 && abs(it.typeHeight - a.typeHeight) <= max(it.typeHeight, a.typeHeight) / 3 }
            val whole = b?.let { a.text.trimEnd() + it.text.trimStart() }
            // Touching, a sentence's pieces; further apart, only where a figure
            // was cut: two labels in a row are both further apart and, read
            // together, as long as a sentence.
            val close = b != null && b.bounds.left - a.bounds.right <= max(a.typeHeight, b.typeHeight) / 2
            if (b == null || whole == null || !((close && (GameLayout.isSentence(a.text) || GameLayout.isSentence(b.text) ||
                    // A figure and its unit cut at the colour change: blue 「32秒」, grey 「間)/」.
                    (a.text.any(Char::isDigit) && b.text.any { it.code >= JAPANESE_FROM }) ||
                    // and words before a blue figure: 「さらに自身の回避値」 「年33.8%増加」.
                    (b.text.any(Char::isDigit) && a.text.any { it.code >= JAPANESE_FROM }) || a.text.any { it in GameLayout.SENTENCE_MARKS } || b.text.any { it in GameLayout.SENTENCE_MARKS })) ||
                    // Or cut where a figure begins: 「さらにHP2」 「6.6%増加」.
                    // or one digit into it, the rest misread (「さらにHP2」 「年26.6%」).
                    ((b.text.trimStart().firstOrNull()?.let { it.isDigit() || it in PARTICLES } == true || a.text.trimEnd().lastOrNull()?.isDigit() == true) &&
                        a.text.any { it.code >= JAPANESE_FROM }))
            ) {
                i++
                continue
            }
            // A question mark straight before a particle was a particle cut
            // in half: 「攻撃速度を」 read 「攻撃速度?」 + 「を…」.
            val head = a.text.trimEnd().let { t -> if (t.lastOrNull()?.let { c -> c in "?？" } == true && b.text.trimStart().firstOrNull()?.let { it in PARTICLES } == true) t.dropLast(1) else t }
            val box = TextBounds(a.bounds.left, min(a.bounds.top, b.bounds.top), b.bounds.right, max(a.bounds.bottom, b.bounds.bottom), a.bounds.space)
            // Read again whole, larger: cut at a colour change, the pieces'
            // edges were misread — 「を」 as 「年」, 「増加」 as 「曽加」.
            val key = "joined@${box.left / 8},${box.top / 8},${box.right / 8}"
            val again = rereads[key]?.firstOrNull()?.text
                ?: (reread(frame, box.left, box.top, box.right, box.bottom, scale = 2) ?: "").also { rereads[key] = listOf(GameLine(it, box, 0.0)) }
            val text = again.takeIf { it.length >= (head.length + b.text.trim().length) * 3 / 4 && it.any { c -> c.code >= JAPANESE_FROM } } ?: (head + b.text.trimStart())
            val joined = a.copy(
                text = text,
                bounds = box,
                backdropError = max(a.backdropError, b.backdropError),
                typeHeight = min(a.typeHeight, b.typeHeight),
            )
            out[i] = joined
            out.remove(b)
        }
        return out
    }

    /**
     * A sentence with figures, read again alone and larger: a skill's blue
     * figures lose a narrow digit at the normal size — 「治癒力を1955増加」 was
     * read 「…955…」 and translated so, 「MK-II」 「MK-I」. The second reading is
     * taken when it holds every figure of the first and more.
     */
    private suspend fun withFiguresReadAgain(frame: Bitmap, line: GameLine): GameLine {
        if (line.text.none(Char::isDigit) || !GameLayout.isSentence(line.text)) return line
        val b = line.bounds
        val key = "figures@${b.left / 8},${b.top / 8},${b.right / 8}:${line.text}"
        val digits = line.text.count(Char::isDigit)
        // More digits, every figure kept; or one stray digit fewer, glued to
        // a katakana, every real figure kept: 「49.4%加算」 was read 「49.4%カ0質算」,
        // the blue 「加」 taken for 「カ0」, and its translation said 「质量算」.
        fun figures(t: String) = Regex("""[0-9]+(?:\.[0-9]+)?""").findAll(t).map { it.value }.toList()
        fun better(again: String): Boolean {
            if (abs(again.length - line.text.length) > max(2, line.text.length / 8)) return false
            val strayIn = { t: String -> Regex("(?<=[゠-ヿ])[0-9](?![0-9.%％])").findAll(t).count() }
            // More digits, but not one glued to a katakana: read again,
            // 「49.4%加算」 became 「49.4%カ0質算」 and was taken for the better.
            if (again.count(Char::isDigit) > digits) return figures(line.text).all { it in again } && strayIn(again) <= strayIn(line.text)
            val stray = strayIn(line.text)
            val strong = figures(line.text).filter { it.length >= 2 }
            return stray > 0 && again.count(Char::isDigit) == digits - stray && strong.all { it in again } &&
                Regex("(?<=[゠-ヿ])[0-9](?![0-9.%％])").findAll(again).none()
        }
        // Alone on its own backdrop first, then larger: the 「1」 of 「1955」
        // came back alone and not at twice the size.
        val again = rereads[key]?.firstOrNull()?.text ?: run {
            val alone = reread(frame, b.left, b.top, b.right, b.bottom) ?: ""
            (if (better(alone)) alone else reread(frame, b.left, b.top, b.right, b.bottom, scale = 2) ?: "")
                .also { rereads[key] = listOf(GameLine(it, b, 0.0)) }
        }
        return if (better(again)) line.copy(text = again) else line
    }

    /**
     * [lines] with two pieces of one word joined: on a row, of one size, no
     * more than 0.4 of the type's height apart. The recogniser came back with
     * 「エピソー」 and 「ド情報」 22 px apart on a 61 px tab, and the tab read
     * 「插曲 ド情報」, half translated. Words set side by side as separate
     * labels stand a glyph or more apart (a skill card's, two titles').
     */
    private fun withWordPiecesJoined(lines: List<GameLine>): List<GameLine> {
        val out = lines.toMutableList()
        var i = 0
        while (i < out.size) {
            val a = out[i]
            val b = out.firstOrNull { o ->
                o !== a && sameRow(o.bounds, a.bounds) && o.bounds.left >= a.bounds.right - a.typeHeight / 4 &&
                    o.bounds.left - a.bounds.right <= max(a.typeHeight, o.typeHeight) * WORD_PIECE_GAP &&
                    min(o.typeHeight, a.typeHeight).toDouble() / max(o.typeHeight, a.typeHeight) >= SAME_TYPE_RATIO &&
                    GameLayout.isLabel(a.text) && GameLayout.isLabel(o.text) &&
                    a.text.any { it.code >= JAPANESE_FROM } && o.text.any { it.code >= JAPANESE_FROM }
            }
            if (b == null) {
                i++
                continue
            }
            out[i] = a.copy(
                text = a.text.trimEnd() + b.text.trimStart(),
                bounds = TextBounds(a.bounds.left, min(a.bounds.top, b.bounds.top), b.bounds.right, max(a.bounds.bottom, b.bounds.bottom), a.bounds.space),
                backdropError = max(a.backdropError, b.backdropError),
                typeHeight = max(a.typeHeight, b.typeHeight),
            )
            out.remove(b)
        }
        return out
    }

    /**
     * [lines] with each bracketed part joined to the word before it on its
     * row. A full-width bracket stands half a glyph off the word, and
     * 「ハナコ(水着)」 came back as 「ハナコ」 and 「(水着)」: the name was
     * translated alone, drawn at the left of its place, and the kanji part
     * left in Japanese after a gap — 「花子　(水着)」 beside 「日奈(礼服)」,
     * read as one, in the next card.
     */
    private fun withBracketedParts(lines: List<GameLine>): List<GameLine> {
        val out = lines.toMutableList()
        for (part in lines) {
            val t = part.text.trim()
            if (t.length < 3 || t.first() !in "(（" || t.last() !in ")）") continue
            val word = out.filter { it !== part && sameRow(it.bounds, part.bounds) && it.bounds.right <= part.bounds.left + part.typeHeight / 2 }
                .maxByOrNull { it.bounds.right }
                ?.takeIf { part.bounds.left - it.bounds.right <= max(it.typeHeight, part.typeHeight) && it.text.none { c -> c in "(（" } }
                ?: continue
            val b = word.bounds
            val joined = word.copy(
                text = word.text.trimEnd() + t,
                bounds = TextBounds(b.left, min(b.top, part.bounds.top), part.bounds.right, max(b.bottom, part.bounds.bottom), b.space),
                backdropError = max(word.backdropError, part.backdropError),
                // The word's type: brackets stand taller than the letters.
                typeHeight = word.typeHeight,
            )
            out[out.indexOf(word)] = joined
            out.remove(part)
        }
        return out
    }

    /**
     * One recognised line, cut where its words stand far apart.
     *
     * ML Kit groups words by height, so two buttons on one row — AUTO and MENU,
     * 120 px apart — come back as one line on some frames and two on others.
     * Each flip read as a new line, withdrew the translation and drew it again
     * (`StableLines`); a gap wider than the line's height is never inside a word.
     */
    private fun separated(line: com.google.mlkit.vision.text.Text.Line): List<Pair<String, android.graphics.Rect>> {
        val box = line.boundingBox ?: return emptyList()
        atIconSymbols(line)?.let { return it }
        atSymbolGaps(line)?.let { return it }
        val elements = line.elements.filter { it.boundingBox != null }
        if (elements.size < 2) return listOf(line.text to box)
        val groups = mutableListOf(mutableListOf(elements.first()))
        for (e in elements.drop(1)) {
            val last = groups.last().last().boundingBox!!
            val now = e.boundingBox!!
            val gap = now.left - last.right
            // Apart, or on another baseline: a carousel's front title and the
            // one of the card behind it, 「第2部メインストーリー」 and 「トリー」,
            // came back as one line whose box spanned both cards, and the
            // translation was set across both, the next card's word in it.
            // Measured: 66 px between the two titles, glyphs of one word
            // 10–25 apart, and the line's box 101 tall around both.
            val h = min(last.height(), now.height())
            // The tighter gap only for titles: a sentence's elements stand
            // apart at its commas and quotes, and cut there a story's line was
            // translated in pieces with the original showing between them.
            val limit = if (line.text.any { it in GameLayout.SENTENCE_MARKS }) box.height() else min(box.height(), (h * WORD_GAP_OF_HEIGHT).toInt())
            if (gap > limit || abs(now.centerY() - last.centerY()) > h / 3) groups += mutableListOf(e) else groups.last() += e
        }
        // A group with no letters — an opening bracket or quote set apart —
        // belongs to its neighbour, not on its own.
        var i = 0
        while (i < groups.size && groups.size > 1) {
            if (groups[i].none { e -> e.text.any(Char::isLetterOrDigit) }) {
                val into = if (i + 1 < groups.size) i + 1 else i - 1
                if (into > i) groups[into].addAll(0, groups[i]) else groups[into].addAll(groups[i])
                groups.removeAt(i)
            } else {
                i++
            }
        }
        if (groups.size == 1) return listOf(line.text to box)
        return groups.map { group ->
            val rect = android.graphics.Rect(group.first().boundingBox!!)
            group.forEach { rect.union(it.boundingBox!!) }
            group.joinToString(" ") { it.text } to rect
        }
    }

    /**
     * A line cut where its characters stand far apart, measured symbol by
     * symbol; null when none do. The recogniser can put a carousel's front
     * title and the end of the one behind it in a single element —
     * 「第2部メインストーリートリー」 on one frame, two elements on another —
     * and the element gap alone missed it. Glyphs of one word stand 10–25 px
     * apart at 76 px; the two titles 66.
     */
    private fun atSymbolGaps(line: com.google.mlkit.vision.text.Text.Line): List<Pair<String, android.graphics.Rect>>? {
        val symbols = line.elements.flatMap { it.symbols }.filter { it.boundingBox != null }
        if (symbols.size < 4 || line.text.any { it in GameLayout.SENTENCE_MARKS }) return null
        val pieces = mutableListOf(mutableListOf(symbols.first()))
        for (sym in symbols.drop(1)) {
            val last = pieces.last().last().boundingBox!!
            val now = sym.boundingBox!!
            val h = min(last.height(), now.height())
            if (h > 0 && now.left - last.right > h * SYMBOL_GAP_OF_HEIGHT) pieces += mutableListOf(sym) else pieces.last() += sym
        }
        // Each piece a word of two letters or more: a label set wide —
        // 「サポータ」 and its 「ー」 stretched to the box's end — is one word.
        if (pieces.size < 2 || pieces.any { p -> p.sumOf { sym -> sym.text.count { it.isLetterOrDigit() && it != 'ー' } } < 2 }) return null
        return pieces.map { p ->
            val rect = android.graphics.Rect(p.first().boundingBox!!)
            p.forEach { rect.union(it.boundingBox!!) }
            p.joinToString("") { it.text } to rect
        }
    }

    /**
     * A line cut at icons the recogniser read as characters inside it, each
     * piece boxed by its own characters; null when there are none. A
     * student's 「(臨戦)」, her rarity stars and her 「STRIKER」 badge came back
     * as one line, 「(臨戦)★食★STRIKE」, and the bracketed part was never
     * joined to the name before it.
     */
    private fun atIconSymbols(line: com.google.mlkit.vision.text.Text.Line): List<Pair<String, android.graphics.Rect>>? {
        val first = line.text.indexOfFirst(::isIcon)
        if (first <= 0) return null
        val pieces = mutableListOf<Pair<StringBuilder, android.graphics.Rect>>()
        var open: Pair<StringBuilder, android.graphics.Rect>? = null
        for (element in line.elements) {
            var newElement = true
            for (symbol in element.symbols) {
                val box = symbol.boundingBox ?: continue
                if (symbol.text.any(::isIcon)) {
                    open = null
                    continue
                }
                val current = open ?: (StringBuilder() to android.graphics.Rect(box)).also { pieces += it; open = it }
                // Latin words keep the space the recogniser put between them.
                if (newElement && current.first.lastOrNull()?.let { it.code < 0x80 && it.isLetterOrDigit() } == true && symbol.text.first().code < 0x80) current.first.append(' ')
                newElement = false
                current.first.append(symbol.text)
                current.second.union(box)
            }
        }
        val kept = pieces.filter { (text, _) -> text.any(Char::isLetterOrDigit) }
        return kept.map { (text, box) -> text.toString() to box }.takeIf { it.isNotEmpty() }
    }

    /**
     * [box] without the columns at either end that stand off the line's
     * panel: a chat bubble's pointed tail the recogniser's box took in. A
     * left bubble's 「いえ」 had its box start 15 px out on the tail, and its
     * translation was set from there, out of the bubble. A column is off the
     * panel where the rows just above and below it are both a different panel
     * from the line's — the page around the bubble.
     */
    private fun onItsPanel(frame: Bitmap, box: TextBounds): TextBounds {
        if (box.top < 2 || box.bottom + 1 >= frame.height || box.left < 0 || box.right > frame.width || box.width < 4) return box
        val above = IntArray(box.width).also { frame.getPixels(it, 0, box.width, box.left, box.top - 2, box.width, 1) }
        val below = IntArray(box.width).also { frame.getPixels(it, 0, box.width, box.left, box.bottom + 1, box.width, 1) }
        val lumas = (above.map { luma(it) } + below.map { luma(it) }).sorted()
        val panel = lumas[lumas.size / 2]
        // Only round a panel: over art nothing above or below is one colour.
        if (lumas.count { abs(it - panel) <= EDGE_PANEL_LUMA } * 3 < lumas.size * 2) return box
        fun off(x: Int) = abs(luma(above[x]) - panel) > OTHER_PANEL_LUMA && abs(luma(below[x]) - panel) > OTHER_PANEL_LUMA
        var left = 0
        while (left < box.width / 4 && off(left)) left++
        var right = box.width
        while (right > box.width * 3 / 4 && off(right - 1)) right--
        if (left == 0 && right == box.width) return box
        return TextBounds(box.left + left, box.top, box.left + right, box.bottom, box.space)
    }

    /**
     * A label cut where its ink stops for longer than a word ever does: two
     * tabs' names side by side — 「ノーマルスキル」 and 「パッシブスキル+」 — read
     * as one run of letters, translated as one, and set squeezed across both
     * tabs and the border between them. Glyphs of a word stand a fraction of
     * the type apart; [INK_GAP_OF_TYPE] of it, inked nowhere, is between
     * words. Each piece takes the letters its share of the width holds.
     */
    private fun atInkGaps(frame: Bitmap, line: GameLine): List<GameLine> {
        // Never a sentence: words in a line of dialogue stand apart too. Nor
        // Latin words, spaced as a sentence's are. A space between Japanese
        // words is two labels: 「ノーマルスキル+ パッシブスキル+」, two skill
        // cards, came back one line and was drawn as one across both.
        val latinSpace = line.text.indices.any { i -> line.text[i] == ' ' && (line.text.getOrNull(i - 1)?.code ?: 0) < 0x80 && (line.text.getOrNull(i + 1)?.code ?: 0) < 0x80 }
        // Two marked labels read as one, a stray 「!」 at the end: 「ノーマルスキル+
        // パッシブスキル+!」 was taken for a sentence and translated as one,
        // with a character's name in it. A 「!」 after a 「+」 is no sentence's.
        val marks = line.text.trimEnd().let { t -> if (Regex("[+＋][!！]$").containsMatchIn(t)) t.dropLast(1) else t }
        if (line.text.length < MIN_GAP_SPLIT_LETTERS || latinSpace || marks.any { it in GameLayout.SENTENCE_MARKS }) return listOf(line)
        val b = line.bounds
        if (b.left < 0 || b.right > frame.width || b.top < 0 || b.bottom > frame.height || b.width < 8) return listOf(line)
        val h = b.height
        val column = IntArray(h)
        val middle = IntArray(b.width).also { frame.getPixels(it, 0, b.width, b.left, b.top + h / 2, b.width, 1) }
            .map { luma(it) }.sorted()[b.width / 2]
        val inked = BooleanArray(b.width) { x ->
            frame.getPixels(column, 0, 1, b.left + x, b.top, 1, h)
            column.count { abs(luma(it) - middle) >= INK_CONTRAST } >= 2
        }
        val minGap = (line.typeHeight * INK_GAP_OF_TYPE).toInt().coerceAtLeast(4)
        val first = inked.indexOfFirst { it }
        val last = inked.indexOfLast { it }
        if (first < 0) return listOf(line)
        val cuts = mutableListOf<Pair<Int, Int>>()
        var x = first
        while (x <= last) {
            if (inked[x]) { x++; continue }
            val start = x
            while (x <= last && !inked[x]) x++
            if (x - start >= minGap) cuts += start to x
        }
        if (cuts.isEmpty()) return listOf(line)
        // Letters by width: each piece gets the characters its ink spans.
        val inkWidth = (last - first + 1).toDouble()
        val n = line.text.length
        val edges = listOf(first) + cuts.flatMap { listOf(it.first, it.second) } + listOf(last + 1)
        val out = mutableListOf<GameLine>()
        var from = 0
        for (i in edges.indices step 2) {
            val l = edges[i]
            val r = edges[i + 1]
            val to = if (i + 2 >= edges.size) n else (n * ((r - first + (edges.getOrNull(i + 2) ?: r) - first) / 2.0) / inkWidth).roundToIntSafe().coerceIn(from + 1, n)
            // A divider between the two read as a stroke goes with neither.
            val text = GameLayout.withoutStrayStroke(line.text.substring(from, to).trim())
            from = to
            if (text.none(Char::isLetterOrDigit)) continue
            out += line.copy(text = text, bounds = TextBounds(b.left + l, b.top, b.left + r, b.bottom, b.space))
        }
        return if (out.size >= 2) out else listOf(line)
    }

    private fun Double.roundToIntSafe(): Int = kotlin.math.round(this).toInt()

    /**
     * A line cut where icons read as characters stand inside it: a student's
     * 「(臨戦)」, her rarity stars and her 「STRIKER」 badge came back as one
     * line, 「(臨戦)★食★STRIKE」, and the bracketed part was never joined to
     * the name before it. The letters before the icons keep the width their
     * characters take, full or half, to the column with the least ink near
     * there; what follows the icons goes with them.
     */
    private fun atIcons(frame: Bitmap, line: GameLine): List<GameLine> {
        val t = line.text
        val first = t.indexOfFirst(::isIcon)
        if (first <= 0 || t.substring(0, first).none(Char::isLetter)) return listOf(line)
        val b = line.bounds
        if (b.left < 0 || b.right > frame.width || b.top < 0 || b.bottom > frame.height) return listOf(line)
        fun units(text: String) = text.sumOf { c -> if (c.isWhitespace()) 0.0 else if (c.code >= FULL_WIDTH_FROM || c in FULL_WIDTH_MARKS || isIcon(c)) 1.0 else HALF_WIDTH }
        val em = b.width / units(t).coerceAtLeast(1.0)
        val estimate = b.left + (em * units(t.substring(0, first))).toInt()
        val column = IntArray(b.height)
        val middle = IntArray(b.width).also { frame.getPixels(it, 0, b.width, b.left, b.top + b.height / 2, b.width, 1) }
            .map { luma(it) }.sorted()[b.width / 2]
        val reach = (em / 2).toInt().coerceAtLeast(1)
        val end = ((estimate - reach).coerceAtLeast(b.left + 1)..(estimate + reach).coerceAtMost(b.right - 1)).minByOrNull { x ->
            frame.getPixels(column, 0, 1, x, b.top, 1, b.height)
            column.count { abs(luma(it) - middle) >= INK_CONTRAST } * 4 + abs(x - estimate) / reach.toDouble()
        } ?: return listOf(line)
        return listOf(GameLine(t.substring(0, first).trim(), TextBounds(b.left, b.top, end, b.bottom, b.space), line.backdropError, line.typeHeight))
    }


    /**
     * A line cut where the recogniser read a divider as a bar: two tabs side
     * by side came back as 「ノーマルスキル||バッシブスキル+」 on one frame and
     * as one run of letters on the next, and the two readings took turns.
     * Each piece gets its share of the box by its characters.
     */
    private fun atDividers(line: GameLine): List<GameLine> {
        val pieces = line.text.split('|', '｜').map { it.trim() }.filter { it.any(Char::isLetter) }
        if (pieces.size < 2 || pieces.any { GameLayout.isSentence(it) }) return listOf(line)
        val total = line.text.length.toDouble()
        val out = mutableListOf<GameLine>()
        var at = 0
        for (piece in pieces) {
            val start = line.text.indexOf(piece, at).coerceAtLeast(at)
            val end = start + piece.length
            at = end
            val l = line.bounds.left + (line.bounds.width * start / total).toInt()
            val r = line.bounds.left + (line.bounds.width * end / total).toInt()
            out += line.copy(text = GameLayout.withoutStrayStroke(piece), bounds = TextBounds(l, line.bounds.top, r, line.bounds.bottom, line.bounds.space))
        }
        return out
    }

    /**
     * [text] without an icon read as its first character, and [box] without
     * the icon: a role's 「◆アタッカー」, a name's affection badge as 「④」.
     * Kept, the icon was painted out with the word and the translation set
     * over where it had been.
     */
    private suspend fun withoutLeadingIcon(frame: Bitmap, text: String, box: TextBounds, kana: Boolean = true): Pair<String, TextBounds> {
        val t = text.trimStart()
        // Or any character standing for a badge in another colour than the
        // word after it: the affection badge, a pink heart, before a
        // student's name came back 「@ナギサ」 and 「Cウイ(水着)」, and the
        // name was translated 「@渚」 with the badge painted out.
        badgeWidth(frame, t, box, kana)?.let { signed ->
            val shift = abs(signed)
            iconBoxes += TextBounds(box.left, box.top, box.left + shift, box.bottom, box.space)
            val rest = TextBounds(box.left + shift, box.top, box.right, box.bottom, box.space)
            if (signed < 0) return t to rest
            // A kana taken for the badge may not be the badge's own reading:
            // the recogniser left the heart out of 「ハナコ」 but not out of its
            // box, and the name lost its 「ハ」. What is beside the badge is
            // read again on its own.
            if (t[0].code >= JAPANESE_FROM) {
                val key = "beside@${rest.left / 8},${rest.top / 8},${rest.right / 8}"
                val again = rereads[key]?.firstOrNull()?.text
                    ?: (reread(frame, rest.left, rest.top, rest.right, rest.bottom) ?: "").also { rereads[key] = listOf(GameLine(it, rest, 0.0)) }
                // Taken only if it gives the letter back: read alone, 「ホシノ」
                // came back 「ホシ.」, worse than the line without its badge.
                val without = t.drop(1).trimStart()
                if (again.length > without.length && again.endsWith(without)) return again to rest
                return without to rest
            }
            return t.drop(1).trimStart() to rest
        }
        // Or a badge's number, read as a digit or two glued to a name in
        // katakana: the affection badge's 「44」 before 「ハナコ(水着)」 came
        // back 「9ハナコ(水着)」, and the name was translated 「9花子」.
        val digits = t.takeWhile { it.isDigit() }.length
        if (digits in 1..2 && t.length > digits + 2 && t[digits] in '゠'..'ヿ' && GameLayout.isLabel(t)) {
            val shift = min(box.height, box.width / 3)
            iconBoxes += TextBounds(box.left, box.top, box.left + shift, box.bottom, box.space)
            return t.drop(digits).trimStart() to TextBounds(box.left + shift, box.top, box.right, box.bottom, box.space)
        }
        // Or a role icon read as one hiragana before a katakana word: the
        // supporter's icon came back 「あサポータ」 and 「もサポーター」, and was
        // translated 「也支援者」. A label is not written so.
        // With a space or not after it (「あ ヒーラー」 became 「啊，治疗师」).
        val word = t.drop(1).trimStart()
        if (kana && word.length >= 4 && t[0] in 'ぁ'..'ゖ' && word.all { it in 'ァ'..'ヺ' || it == 'ー' }) {
            val shift = min(box.height, box.width / 3)
            iconBoxes += TextBounds(box.left, box.top, box.left + shift, box.bottom, box.space)
            return word to TextBounds(box.left + shift, box.top, box.right, box.bottom, box.space)
        }
        if (t.length < 3 || !isIcon(t[0]) || !t[1].isLetter()) return text to box
        val shift = min(box.height, box.width / 3)
        iconBoxes += TextBounds(box.left, box.top, box.left + shift, box.bottom, box.space)
        return t.drop(1).trimStart() to TextBounds(box.left + shift, box.top, box.right, box.bottom, box.space)
    }

    /**
     * How far into [box] a badge or icon before [text]'s word reaches; negative
     * when the icon is in the box but not in the text, so no letter goes with
     * it. Null when there is none.
     */
    private suspend fun badgeWidth(frame: Bitmap, text: String, box: TextBounds, kana: Boolean): Int? {
        // Before a Japanese word only. The heart came back as 「@」, 「C」 and
        // 「ワ」 (「ワホシノ(水着)」); a kana is taken only by colour like a
        // Latin letter, and a crosshair icon before 「アタッカー」, ink of the
        // word's own colour, leaves its 「ア」 alone.
        // Two letters are enough for a kana: 「ミカ」 had the heart painted out
        // and her name drawn from its edge.
        if (text.length < 2 || text[1].isWhitespace() || text[1].code < JAPANESE_FROM) return null
        if (text.length < 3 && text[0].code < JAPANESE_FROM) return null
        // A sign — 「@」 — never begins a word; a letter might (「Cランク」),
        // so it is taken only where its ink is another colour. A quote does
        // begin one: a story's lines are set in 「"カッコイイ……。"」.
        if (!text[0].isLetterOrDigit() && text[0].code < JAPANESE_FROM) {
            return if (text[0] in LOST_START_MARKS || text[0] in OPENING_MARKS) null else min(box.height, box.width / 3)
        }
        if (box.left < 0 || box.top < 0 || box.right > frame.width || box.bottom > frame.height) return null
        // The first glyph's width against what follows, by the colour of their
        // ink: a badge is not one stretch of ink — the heart's outline, its
        // fill and its number came back as five — so it is taken by place.
        val glyph = min(box.height, box.width / 3)
        val first = inkColour(frame, box.left, box.top, box.left + glyph, box.bottom) ?: return null
        // Against the next two glyphs, not the whole rest: a name and the
        // affiliation after it in another colour were one line, and the name's
        // first letter was the colour of neither.
        val rest = inkColour(frame, box.left + glyph, box.top, min(box.right, box.left + 3 * glyph), box.bottom) ?: return null
        val d = kotlin.math.sqrt((0..2).sumOf { (first[it] - rest[it]) * (first[it] - rest[it]) })
        if (d <= BADGE_COLOUR) return null
        // A bar in another colour before a title — a pop-up's accent — is a
        // few columns of ink and a gap before the word: the title's patch
        // painted it out. Its width, and no letter lost.
        bar(frame, box)?.let { return -it }
        if (text[0].code < JAPANESE_FROM) return glyph
        if (!kana) return null
        // A kana in another colour is a badge only if, read alone, it is the
        // badge's number: 「リオ」 beside its avatar lost its 「リ」 by colour.
        val key = "badge:${text[0]}@${box.left / 8},${box.top / 8}"
        val number = rereads[key]?.firstOrNull()?.text
            ?: (reread(frame, box.left, box.top, box.left + glyph, box.bottom, scale = 2) ?: "").also {
                rereads[key] = listOf(GameLine(it, box, 0.0))
            }
        return when {
            number.isNotEmpty() && number.all(Char::isDigit) -> glyph
            // Nothing to read there: an icon in the box but not in the text
            // (the student list's book before 「生徒リスト」, painted out and
            // the title drawn over its place). Its width, and no letter lost.
            // An icon, not a divider: the blue bar before a list's heading
            // 「全生徒」 is a few columns of ink.
            // And read as a mark, not as nothing: alone, a kanji the line
            // begins with may read as nothing at all (「画像はイメージです」
            // lost its 「画」 to a patch).
            number.isNotBlank() && number.none(Char::isLetterOrDigit) &&
                inkedColumns(frame, box.left, box.top, box.left + glyph, box.bottom) * 2 >= glyph -> -glyph
            else -> null
        }
    }

    /**
     * Where an icon just before [box] ends, when its ink is another colour
     * than the line's: the patch stops there. The affection badge before
     * 「ユズ」 was outside the recogniser's box, nothing read it, and the
     * name's patch grew left over it and painted it out.
     */
    private fun iconBefore(frame: Bitmap, box: TextBounds, typeHeight: Int): Int? {
        val reach = (typeHeight * 1.5).toInt()
        val left = (box.left - reach).coerceAtLeast(0)
        if (box.left - left < 4 || box.top < 0 || box.bottom > frame.height) return null
        val own = inkColour(frame, box.left, box.top, min(box.right, box.left + typeHeight * 2), box.bottom) ?: return null
        val column = IntArray(box.height)
        var x = box.left - 1
        while (x >= left) {
            frame.getPixels(column, 0, 1, x, box.top, 1, box.height)
            val middle = column.map { luma(it) }.sorted()[box.height / 2]
            val ink = column.filter { abs(luma(it) - middle) >= INK_CONTRAST }
            if (ink.size >= 2) {
                val r = ink.sumOf { Color.red(it) }.toDouble() / ink.size - own[0]
                val g = ink.sumOf { Color.green(it) }.toDouble() / ink.size - own[1]
                val bl = ink.sumOf { Color.blue(it) }.toDouble() / ink.size - own[2]
                if (kotlin.math.sqrt(r * r + g * g + bl * bl) <= BADGE_COLOUR) return null
                // An icon is wider than a panel's edge: a dialogue box's
                // border, a column or two, is not one.
                return if (inkedColumns(frame, (x - MIN_ICON_PX + 1).coerceAtLeast(0), box.top, x + 1, box.bottom) >= MIN_ICON_PX) x + 1 else null
            }
            x--
        }
        return null
    }

    /**
     * How far into [box] a bar at its start reaches with the gap after it, or
     * null: a first run of ink no wider than a quarter of the line's height,
     * then blank columns.
     */
    private fun bar(frame: Bitmap, box: TextBounds): Int? {
        val h = box.height
        val column = IntArray(h)
        fun inked(x: Int): Boolean {
            frame.getPixels(column, 0, 1, x, box.top, 1, h)
            val middle = column.map { luma(it) }.sorted()[h / 2]
            return column.count { abs(luma(it) - middle) >= INK_CONTRAST } * 2 >= h
        }
        var x = box.left
        while (x < box.right && !inked(x)) x++
        val start = x
        while (x < box.right && inked(x)) x++
        if (x == start || x - start > h / 4) return null
        val end = x
        frame.getPixels(column, 0, 1, x, box.top, 1, h)
        while (x < box.right && column.let { frame.getPixels(it, 0, 1, x, box.top, 1, h); val m = it.map { p -> luma(p) }.sorted()[h / 2]; it.none { p -> abs(luma(p) - m) >= INK_CONTRAST } }) x++
        return if (x - end >= BAR_GAP_PX) x - box.left else null
    }

    /**
     * Where a coloured mark inside [box], before its first ink, ends: a
     * pop-up title's accent bar, light cyan on white, too pale to count as
     * ink, sat inside the recogniser's box and was painted out with it.
     * Null when there is none.
     */
    private fun colouredMarkInside(frame: Bitmap, box: TextBounds): Int? {
        val h = box.height
        if (h <= 0 || box.left < 0 || box.right > frame.width || box.top < 0 || box.bottom > frame.height) return null
        val column = IntArray(h)
        fun inkAt(x: Int): Boolean {
            frame.getPixels(column, 0, 1, x, box.top, 1, h)
            val middle = column.map { luma(it) }.sorted()[h / 2]
            return column.count { abs(luma(it) - middle) >= INK_CONTRAST } >= 2
        }
        val firstInk = (box.left until box.right).firstOrNull(::inkAt) ?: return null
        if (firstInk - box.left < 4) return null
        frame.getPixels(column, 0, 1, firstInk - 2, box.top, 1, h)
        val panel = column[h / 2]
        var end: Int? = null
        for (x in box.left until firstInk - 2) {
            frame.getPixels(column, 0, 1, x, box.top, 1, h)
            val marked = column.count { p ->
                val dr = Color.red(p) - Color.red(panel)
                val dg = Color.green(p) - Color.green(panel)
                val db = Color.blue(p) - Color.blue(panel)
                dr * dr + dg * dg + db * db > MARK_COLOUR * MARK_COLOUR
            }
            if (marked * 2 >= h) end = x + 1
        }
        return end?.plus(SIDE_MARGIN_PX)
    }

    /** How many columns of a box hold ink against their own middle value. */
    private fun inkedColumns(frame: Bitmap, left: Int, top: Int, right: Int, bottom: Int): Int {
        val h = bottom - top
        if (right <= left || h <= 0) return 0
        val column = IntArray(h)
        return (left until right).count { x ->
            frame.getPixels(column, 0, 1, x, top, 1, h)
            val middle = column.map { luma(it) }.sorted()[h / 2]
            column.count { abs(luma(it) - middle) >= INK_CONTRAST } >= 2
        }
    }

    /** The mean colour (r, g, b) of the ink in a box, by each column's middle value; null without ink. */
    private fun inkColour(frame: Bitmap, left: Int, top: Int, right: Int, bottom: Int): DoubleArray? {
        val h = bottom - top
        if (right <= left || h <= 0) return null
        val column = IntArray(h)
        val sum = DoubleArray(3)
        var n = 0
        for (x in left until right) {
            frame.getPixels(column, 0, 1, x, top, 1, h)
            val middle = column.map { luma(it) }.sorted()[h / 2]
            for (p in column) if (abs(luma(p) - middle) >= INK_CONTRAST) {
                sum[0] += Color.red(p).toDouble(); sum[1] += Color.green(p).toDouble(); sum[2] += Color.blue(p).toDouble(); n++
            }
        }
        return if (n == 0) null else DoubleArray(3) { sum[it] / n }
    }

    /**
     * [text] with a first letter the recogniser lost given back, [text] when
     * it lost none, or null when it cannot be had: a word does not begin
     * with a lengthening mark. Small italic 「ノーマルスキル」 came back
     * 「-マルスキル」 and was translated 「玛尔斯技能」, Mars skill; it is read
     * again on its own at twice the size, and a reading that still begins
     * with the mark is left untranslated rather than translated as another
     * word.
     */
    private suspend fun withLostStart(frame: Bitmap, text: String, box: TextBounds): String? {
        if (text.length < 2 || text[0] !in LOST_START_MARKS || text[1] !in '゠'..'ヿ') return text
        val key = "lost:$text@${box.left / 8},${box.top / 8}"
        rereads[key]?.let { return it.firstOrNull()?.text }
        val again = reread(frame, box.left - box.height, box.top, box.right, box.bottom, scale = 2)
            ?.filterNot { it.isWhitespace() }
            ?.takeIf { it.isNotEmpty() && it[0] !in LOST_START_MARKS && it.endsWith(text.drop(1)) && it.length > text.length - 1 }
        rereads[key] = listOfNotNull(again?.let { GameLine(it, box, 0.0) })
        return again
    }

    /** What the recogniser reads in columns [left, right) on a margin of their own backdrop, scaled; null when it fails. */
    private suspend fun reread(frame: Bitmap, left: Int, top: Int, right: Int, bottom: Int, scale: Int = 1): String? {
        val l = left.coerceAtLeast(0)
        val r = right.coerceAtMost(frame.width)
        if (r - l < MIN_PIECE_PX || bottom <= top) return null
        val crop = padded(frame, l, top, r, bottom, pad = (bottom - top) / 4)
        val sized = if (scale == 1) crop else Bitmap.createScaledBitmap(crop, crop.width * scale, crop.height * scale, true)
        return try {
            duty.timed { recognizer.process(InputImage.fromBitmap(sized, 0)).await() }.text.replace("\n", "").trim()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            null
        } finally {
            if (sized !== crop) sized.recycle()
            crop.recycle()
        }
    }

    /**
     * One line of the recogniser's cut where its type changes size and
     * colour together, each piece read again on its own; null when it does
     * not. A skill's small blue-grey label in its card and the skill's name
     * in large dark type beside it came back as one line, 「ノーマルスキル決定的
     * な手がかり」, and were translated and painted as one across both. Both
     * signals, because either alone happens inside one piece of text: a
     * coloured word in a sentence, a 「……」 lower than the letters after it.
     */
    private suspend fun atTypeChange(frame: Bitmap, box: TextBounds): List<Pair<String, TextBounds>>? {
        val h = box.height
        if (h <= 0 || box.left < 0 || box.top < 0 || box.right > frame.width || box.bottom > frame.height || box.width < h * MIN_SPLIT_ASPECT) return null
        val key = "size@${box.left / 8},${box.top / 8},${box.right / 8},${box.bottom / 8}"
        rereads[key]?.let { cached -> return cached.takeIf { it.size > 1 }?.map { it.text to it.bounds } }
        val w = box.width
        val pixels = IntArray(w * h)
        frame.getPixels(pixels, 0, w, box.left, box.top, w, h)
        val column = DoubleArray(h)
        val tops = IntArray(w) { -1 }
        val bottoms = IntArray(w) { -1 }
        val colours = Array(w) { LongArray(4) }
        for (x in 0 until w) {
            for (y in 0 until h) column[y] = luma(pixels[y * w + x])
            val bg = column.sortedArray()[h / 2]
            for (y in 0 until h) if (abs(column[y] - bg) >= INK_CONTRAST) {
                if (tops[x] < 0) tops[x] = y
                bottoms[x] = y
                val p = pixels[y * w + x]
                colours[x][0] += Color.red(p).toLong()
                colours[x][1] += Color.green(p).toLong()
                colours[x][2] += Color.blue(p).toLong()
                colours[x][3]++
            }
        }
        // Each side's type height and ink colour; null with too little ink to
        // judge. The height is the tall end of its columns' ink, not the
        // tallest: a card's slanted edge beside a label is ink from top to
        // bottom, a few columns wide.
        fun side(from: Int, to: Int): DoubleArray? {
            val heights = (from until to).filter { tops[it] >= 0 }.map { bottoms[it] - tops[it] + 1 }.sorted()
            val sum = LongArray(4)
            for (x in from until to) for (i in 0..3) sum[i] += colours[x][i]
            if (heights.size < h / 2 || sum[3] == 0L) return null
            val tall = heights[(heights.size * TYPE_HEIGHT_PERCENTILE).toInt()].toDouble()
            return doubleArrayOf(tall, sum[0].toDouble() / sum[3], sum[1].toDouble() / sum[3], sum[2].toDouble() / sum[3])
        }
        val minGap = (h * TYPE_CHANGE_GAP_OF_HEIGHT).toInt().coerceAtLeast(4)
        var cut: Pair<Int, Int>? = null
        var x = 0
        while (x < w && cut == null) {
            if (tops[x] >= 0) {
                x++
                continue
            }
            val start = x
            while (x < w && tops[x] < 0) x++
            if (x - start < minGap || start == 0 || x >= w) continue
            // The type either side of the gap, a few glyphs of it: a speaker's
            // large name further left made a small affiliation's katakana and
            // its kanji look like two sizes of type, and 「ワイルドハント芸術学院」
            // was cut in two.
            val reach = (h * TYPE_CHANGE_REACH_OF_HEIGHT).toInt()
            val a = side((start - reach).coerceAtLeast(0), start) ?: continue
            val b = side(x, (x + reach).coerceAtMost(w)) ?: continue
            val sizes = min(a[0], b[0]) / max(a[0], b[0])
            val colour = kotlin.math.sqrt((a[1] - b[1]) * (a[1] - b[1]) + (a[2] - b[2]) * (a[2] - b[2]) + (a[3] - b[3]) * (a[3] - b[3]))
            if (sizes in MIN_TYPE_CHANGE..MAX_TYPE_CHANGE && colour > TYPE_CHANGE_COLOUR) cut = start to x
        }
        val pieces = cut?.let { (gapStart, gapEnd) ->
            val l = reread(frame, box.left, box.top, box.left + gapStart, box.bottom)
            val r = reread(frame, box.left + gapEnd, box.top, box.right, box.bottom)
            if (l.isNullOrBlank() || r.isNullOrBlank()) null else listOf(
                GameLine(l, TextBounds(box.left, box.top, box.left + gapStart, box.bottom, box.space), 0.0),
                GameLine(r, TextBounds(box.left + gapEnd, box.top, box.right, box.bottom, box.space), 0.0),
            )
        }.orEmpty()
        rereads[key] = pieces
        return pieces.takeIf { it.size > 1 }?.map { it.text to it.bounds }
    }

    /**
     * Where icons were taken off lines' starts, kept to bound the lines'
     * patches: grown to the ink, the name's patch took the badge in again.
     */
    private val iconBoxes = mutableListOf<TextBounds>()

    private fun isIcon(c: Char): Boolean = c in '①'..'⓿' || c in '■'..'◿' || c in '☀'..'⛿' ||
        c in '⊕'..'⊙' || c in '❤'..'❥' || c in '㉑'..'㉟' || c in '㊱'..'㊿'

    /**
     * [text] without a last mark that was never there: the tail of a chat
     * bubble the text nearly fills. MomoTalk's 「それはよかった」 was read now
     * and then as 「それはよかった?」 — the box the same, its end on the
     * bubble's edge and the pointed tail past it — and translated as a
     * question. A real 「?」 has type in the line's last glyph's width; there
     * a read mark has nothing but the page running in from the box's end.
     */
    private fun withoutTail(frame: Bitmap, text: String, box: TextBounds): Pair<String, TextBounds> {
        val trimmed = text.trimEnd()
        val last = trimmed.lastOrNull() ?: return text to box
        if (last !in TAIL_MARKS || trimmed.length < 2) return text to box
        val tail = (box.height * TAIL_WIDTH_OF_HEIGHT).toInt().coerceAtMost(box.width / 3)
        if (tail < 2 || box.left < 0 || box.right > frame.width || box.top < 2 || box.bottom + 1 >= frame.height) return text to box
        // The panel as the middle of everything in the line before its end:
        // type is the lesser part of a line's area. Not just above and below
        // it — a bubble the text nearly fills has the page there, its white
        // type read as no ink, and a real 「？」 in 「バグ？」 was taken for a tail.
        val body = IntArray(box.width - tail)
        val lumas = mutableListOf<Double>()
        for (y in box.top until box.bottom step 2) {
            frame.getPixels(body, 0, body.size, box.left, y, body.size, 1)
            for (x in body.indices step 2) lumas += luma(body[x])
        }
        val panel = lumas.sorted()[lumas.size / 2]
        val sign = if (panel < MID_LUMA) 1.0 else -1.0
        val row = IntArray(tail)
        var ink = 0
        var filled = 0
        for (y in box.top until box.bottom) {
            frame.getPixels(row, 0, tail, box.right - tail, y, tail, 1)
            val inked = BooleanArray(tail) { (luma(row[it]) - panel) * sign >= INK_CONTRAST }
            filled += inked.count { it }
            // Not what runs in from the box's end: the page past the bubble.
            var end = tail
            while (end > 0 && inked[end - 1]) end--
            for (x in 0 until end) if (inked[x]) ink++
        }
        val share = ink.toDouble() / (tail * box.height)
        // Filled: a help button, a dark square with a light 「?」 in it, read
        // as the word's question. 「生徒?」 over the student list was taken
        // for a sentence and translated 「学生?|」. The button is not the
        // word's, so its place leaves the box.
        if (filled.toDouble() / (tail * box.height) >= BUTTON_INK && last in "?？") {
            val column = IntArray(box.height)
            var x = box.right - 1
            while (x > box.left + box.width / 2) {
                frame.getPixels(column, 0, 1, x, box.top, 1, box.height)
                if (column.count { (luma(it) - panel) * sign >= INK_CONTRAST } * 2 < box.height) break
                x--
            }
            // Past the button's own edge, and the gap before it.
            while (x > box.left + box.width / 2) {
                frame.getPixels(column, 0, 1, x, box.top, 1, box.height)
                if (column.any { (luma(it) - panel) * sign >= INK_CONTRAST }) break
                x--
            }
            return trimmed.dropLast(1).trimEnd() to TextBounds(box.left, box.top, x + 1, box.bottom, box.space)
        }
        if (share >= TAIL_MAX_INK) return text to box
        return trimmed.dropLast(1).trimEnd() to box
    }

    /**
     * How plain the line's backdrop is: the band just above and below it,
     * predicted row by row from the pixels at the line's two ends. Mean
     * absolute error in brightness; see [GameLine.backdropError].
     */
    private fun backdropError(frame: Bitmap, width: Int, height: Int, box: TextBounds): Double {
        val pad = max(6, box.height / 4)
        val left = box.left - 1
        val right = box.right
        if (left < 0 || right >= width) return Double.MAX_VALUE
        var sum = 0.0
        var count = 0
        val rowPixels = IntArray(width)
        val rows = (box.top - pad until box.top) + (box.bottom until box.bottom + pad)
        for (y in rows) {
            if (y < 0 || y >= height) continue
            // One row at a time: the whole frame as an int array is 18 MB.
            frame.getPixels(rowPixels, 0, width, 0, y, width, 1)
            val pixels = rowPixels
            val row = 0
            var l = luma(pixels[row + left])
            var r = luma(pixels[row + right])
            // One end off the panel the rest of the row is on: the box ran a
            // few pixels past a bubble the text nearly fills — MomoTalk's
            // 「それはよかった」 ended on the white page, and its blue bubble
            // scored 63, as art. That end takes the other's colour; over art
            // the row between them is what still tells.
            val middle = (box.left until right step SAMPLE_STEP).map { luma(pixels[row + it]) }.sorted().let { it[it.size / 2] }
            if (abs(r - middle) > EDGE_PANEL_LUMA && abs(l - middle) <= EDGE_PANEL_LUMA) r = l
            else if (abs(l - middle) > EDGE_PANEL_LUMA && abs(r - middle) <= EDGE_PANEL_LUMA) l = r
            // Both ends off it, and alike: the box ran past the bubble at
            // both ends, onto the page around it. A graded panel's ends
            // differ, and stay as they are.
            else if (abs(l - middle) > EDGE_PANEL_LUMA && abs(r - middle) > EDGE_PANEL_LUMA && abs(l - r) <= EDGE_PANEL_LUMA) {
                l = middle
                r = middle
            }
            val span = (right - left).coerceAtLeast(1)
            var x = box.left
            while (x < right) {
                val t = (x - left).toDouble() / span
                sum += abs(luma(pixels[row + x]) - (l * (1 - t) + r * t))
                count++
                x += SAMPLE_STEP
            }
        }
        return if (count == 0) Double.MAX_VALUE else sum / count
    }

    /**
     * The tallest band of inked rows in [box], a gap of [TYPE_ROW_GAP] rows
     * allowed inside it: the height of the type, without ruby over it or the
     * slack the recogniser's box sometimes takes in. See [GameLine.typeHeight].
     */
    private fun typeHeight(frame: Bitmap, box: TextBounds): Int {
        val h = box.height
        if (h <= 0 || box.width <= 0) return h
        // The panel is read just outside the box, above and below each
        // column. The column's own middle value is not the panel wherever a
        // stroke fills most of a tight box, and judged against it those
        // strokes vanished: a line of dialogue measured 37 px for 55.
        val above = (box.top - 2).coerceAtLeast(0)
        val below = (box.bottom + 1).coerceAtMost(frame.height - 1)
        val outsideTop = IntArray(box.width).also { frame.getPixels(it, 0, box.width, box.left, above, box.width, 1) }
        val outsideBottom = IntArray(box.width).also { frame.getPixels(it, 0, box.width, box.left, below, box.width, 1) }
        // Where above and below differ, the one nearer the line's own middle
        // colour: a bubble the text nearly fills had the white page just
        // under its box, the mean of blue and white read as a light panel,
        // no white type stood out of it, and 「くっ……」 measured as tall as
        // its box — 63 px for 40.
        val middle = IntArray(box.width).also { frame.getPixels(it, 0, box.width, box.left, box.top + h / 2, box.width, 1) }
            .map { luma(it) }.sorted()[box.width / 2]
        val panel = DoubleArray(box.width) {
            val a = luma(outsideTop[it])
            val b = luma(outsideBottom[it])
            if (abs(a - b) <= OTHER_PANEL_LUMA) (a + b) / 2 else if (abs(a - middle) <= abs(b - middle)) a else b
        }
        // Ink stands out of its panel: light on a dark one.
        val sign = if (panel.average() < MID_LUMA) 1.0 else -1.0

        val column = IntArray(h)
        val ink = IntArray(h)
        for (x in 0 until box.width) {
            frame.getPixels(column, 0, 1, box.left + x, box.top, 1, h)
            for (y in 0 until h) if ((luma(column[y]) - panel[x]) * sign >= INK_CONTRAST) ink[y]++
        }
        // A row is inside the type when it holds a little of the busiest
        // row's ink: across a long line only a few glyphs reach the top rows.
        // The tallest band of such rows, a few empty ones allowed inside it;
        // ruby over the line stands further off and is not counted.
        val enough = max(2, (ink.maxOrNull() ?: 0) / TYPE_ROW_SHARE)
        var best = 0
        var start = -1
        var last = -1
        for (y in 0 until h) {
            if (ink[y] < enough) continue
            if (start < 0 || y - last > TYPE_ROW_GAP + 1) start = y
            last = y
            best = max(best, y - start + 1)
        }
        // Never far under the recogniser's box: over art the rows outside the
        // box are not panel, the ink is judged against the wrong side, and a
        // name tab's type measured 32 px in a 75 px box — its translation was
        // set at half the size of the name it replaced. Type fills at least
        // two thirds of a box around one line.
        return if (best == 0) h else max(best, (h * MIN_TYPE_OF_BOX).toInt())
    }

    /** What a line was split into last time, so a still line is not re-read every frame. */
    private val splits = object : LinkedHashMap<String, List<GameLine>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<GameLine>>?) = size > SPLIT_CACHE
    }

    /**
     * Lines read again in pieces or larger ([atTypeChange], [withLostStart]),
     * and lines looked at and left whole. Every line of a whole reading is
     * looked at, a screen holds fifty, and the pieces are read by the
     * recogniser: among [splits]' 64 they would be pushed out and read again
     * on every whole reading.
     */
    private val rereads = object : LinkedHashMap<String, List<GameLine>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<GameLine>>?) = size > REREAD_CACHE
    }

    /**
     * A line set in two colours — a speaker's name and their affiliation —
     * read again piece by piece (`GameLayout.splitPoints`). The recogniser
     * returns the two as one line and one word, so the cut can only be found
     * in the pixels, and each piece's words only by reading it alone.
     */
    private suspend fun splitByColour(frame: Bitmap, line: GameLine): List<GameLine> {
        val b = line.bounds
        if (b.width < b.height * MIN_SPLIT_ASPECT) return listOf(line)
        val key = "${line.text}@${b.left / 8},${b.top / 8},${b.right / 8}"
        splits[key]?.let { return it }

        // One cut, short of the line's end: a name and what follows it. A cut
        // further along was measured cutting a white line of dialogue in two
        // where the art behind it changed colour.
        val cuts = GameLayout.splitPoints(inkRuns(frame, b), b.height)
            .filter { it - b.left <= b.width * MAX_NAME_FRACTION }
            .take(1)
        val pieces = if (cuts.isEmpty()) {
            listOf(line)
        } else {
            val edges = listOf(b.left) + cuts + listOf(b.right)
            val read = edges.zipWithNext().mapNotNull { (l, r) ->
                if (r - l < MIN_PIECE_PX) return@mapNotNull null
                // ML Kit refuses anything under 32 px a side, and the refusal
                // used to throw out of the whole frame's reading — measured,
                // 43 frames of a story scene read as nothing. A margin of the
                // frame around the piece clears that and keeps the glyphs' edges.
                val crop = padded(frame, l, b.top, r, b.bottom, pad = b.height / 4)
                val text = try {
                    duty.timed { recognizer.process(InputImage.fromBitmap(crop, 0)).await() }.text.replace("\n", "").trim()
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (failure: Exception) {
                    ""
                } finally {
                    crop.recycle()
                }
                if (text.isBlank()) {
                    null
                } else {
                    // Each piece's own type: an affiliation is set smaller
                    // than the name beside it.
                    val pieceBounds = TextBounds(l, b.top, r, b.bottom, b.space)
                    GameLine(text, pieceBounds, line.backdropError, typeHeight(frame, pieceBounds))
                }
            }
            // A piece that read as nothing means the cut was wrong: keep the whole.
            if (read.size == edges.size - 1) read else listOf(line)
        }
        splits[key] = pieces
        return pieces
    }

    /**
     * Columns [left, right) of [frame], with a margin of the piece's own
     * backdrop around them. A margin taken from the frame reached into the
     * next piece and read its edge: 「アコ,」, 「|風紀委員会」, 「1風紀委員会」.
     */
    private fun padded(frame: Bitmap, left: Int, top: Int, right: Int, bottom: Int, pad: Int): Bitmap {
        val t = (top - pad).coerceAtLeast(0)
        val btm = (bottom + pad).coerceAtMost(frame.height)
        val piece = Bitmap.createBitmap(frame, left, t, right - left, btm - t)
        val w = max(right - left + 2 * pad, MIN_RECOGNISABLE_PX)
        val h = max(btm - t, MIN_RECOGNISABLE_PX)
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(out)
        canvas.drawColor(backdropOf(piece))
        canvas.drawBitmap(piece, ((w - piece.width) / 2).toFloat(), ((h - piece.height) / 2).toFloat(), null)
        piece.recycle()
        return out
    }

    /** The middle colour of the piece's edge columns, where the cut put backdrop. */
    private fun backdropOf(piece: Bitmap): Int {
        val edge = IntArray(piece.height * 2)
        piece.getPixels(edge, 0, 1, 0, 0, 1, piece.height)
        piece.getPixels(edge, piece.height, 1, piece.width - 1, 0, 1, piece.height)
        return edge.sortedBy { luma(it) }[edge.size / 2]
    }

    /**
     * Runs of inked columns across [box], each with its mean ink colour.
     *
     * Ink is what stands out of its column **on the line's own side**: lighter
     * than the backdrop if most of the contrast is lighter, darker otherwise.
     * Counting both took in the glyphs' dark outline and the art showing
     * through it, and a thin white リ came out grey — measured, the name
     * イオリ was cut after イオ, every time, and チナツ not at all.
     */
    private fun inkRuns(frame: Bitmap, box: TextBounds, lighterInk: Boolean? = null): List<GameLayout.InkRun> {
        val w = box.width
        val h = box.height
        if (w <= 0 || h <= 0) return emptyList()
        val pixels = IntArray(w * h)
        frame.getPixels(pixels, 0, w, box.left, box.top, w, h)
        val background = DoubleArray(w)
        val column = DoubleArray(h)
        var lighter = 0L
        var darker = 0L
        for (x in 0 until w) {
            for (y in 0 until h) column[y] = luma(pixels[y * w + x])
            val bg = column.sortedArray()[h / 2]
            background[x] = bg
            for (y in 0 until h) {
                if (column[y] - bg >= INK_CONTRAST) lighter++ else if (bg - column[y] >= INK_CONTRAST) darker++
            }
        }
        val sign = if (lighterInk ?: (lighter >= darker)) 1.0 else -1.0

        val runs = mutableListOf<GameLayout.InkRun>()
        var start = -1
        var r = 0L
        var g = 0L
        var bl = 0L
        var n = 0L
        fun close(end: Int) {
            if (start >= 0 && n > 0) {
                runs += GameLayout.InkRun(box.left + start, box.left + end, (r / n).toInt(), (g / n).toInt(), (bl / n).toInt(), n.toInt())
            }
            start = -1; r = 0; g = 0; bl = 0; n = 0
        }
        for (x in 0 until w) {
            var inked = false
            for (y in 0 until h) {
                val p = pixels[y * w + x]
                if ((luma(p) - background[x]) * sign >= INK_CONTRAST) {
                    r += Color.red(p); g += Color.green(p); bl += Color.blue(p); n++
                    inked = true
                }
            }
            if (inked) {
                if (start < 0) start = x
            } else {
                close(x - 1)
            }
        }
        close(w - 1)
        return runs
    }

    private fun luma(pixel: Int): Double =
        (Color.red(pixel) * 299 + Color.green(pixel) * 587 + Color.blue(pixel) * 114) / 1000.0

    /**
     * How the line looks, for drawing its translation in its place, and the
     * line with its bounds grown out to all of its ink: the ink's
     * colour, the type's height, and — on a panel plain enough to paint back —
     * the backdrop row by row, so the original can be erased
     * (`docs/features/v3-game-translation.md`, "erase and redraw").
     */
    /**
     * One weight for a kind of line, not one per line: measured per line, the
     * strokes of one game's dialogue came out 0.13 on one line and 0.16 on the
     * next, and consecutive lines of one box were set in two weights. Names
     * and dialogue each take the middle of their first measurements, and it
     * then stays: a running mean drifted across a step in weight in the
     * middle of a chat, and the lines settled after it came out bolder.
     */
    private val labelWeights = mutableListOf<Float>()
    private val lineWeights = mutableListOf<Float>()

    private fun weightOf(label: Boolean): Float? =
        (if (label) labelWeights else lineWeights).takeIf { it.isNotEmpty() }?.sorted()?.let { it[it.size / 2] }

    /**
     * One size for one size of type, not one per line: the height of a line's
     * ink depends on its letters — a chat's 「はい」 measured 37 px, its
     * bubbles of kanji 41, in one font of one size — and each translation was
     * set at its own line's measure. Sizes within [SAME_TYPE_RATIO] of a size
     * already seen are that size, the tallest measured, which is the type's.
     * Names and lines apart: a game sets its names smaller or larger.
     */
    private class TypeSize(val speaker: Boolean, var smallest: Int, var tallest: Int) {
        /** The type's size measured along the line, one per line that allowed it. */
        val ems = ArrayDeque<Double>()
    }
    private val typeSizes = mutableListOf<TypeSize>()

    /**
     * The size to set a line of this kind at, as the glyph height the
     * renderer sizes from. Taken from the type's width where the line's
     * characters allow it: ink height had a chat's bubbles set 13% larger
     * than the original — 41 px per character across, 46.6 px set — and the
     * short ones (「先生」 → 「老师」) then no longer fitted and were shrunk.
     */
    private fun steadySize(speaker: Boolean, measured: Int, em: Double?): Int {
        // Grouped by the ink's height, within the ratio of the smallest and
        // the tallest alike, so a size cannot creep up into the next one.
        var size = typeSizes.firstOrNull { t ->
            t.speaker == speaker && min(t.smallest, measured).toDouble() / max(t.tallest, measured) >= SAME_TYPE_RATIO
        }
        if (size == null) {
            size = TypeSize(speaker, measured, measured)
            typeSizes += size
        }
        size.smallest = min(size.smallest, measured)
        size.tallest = max(size.tallest, measured)
        if (em != null) {
            size.ems.addLast(em)
            while (size.ems.size > MAX_EM_SAMPLES) size.ems.removeFirst()
        }
        if (size.ems.isEmpty()) return size.tallest
        val typical = size.ems.sorted()[size.ems.size / 2]
        return (typical * GLYPH_OF_EM).toInt()
    }

    /**
     * The type's size from the line's width: full-width characters are one
     * size across, others about half. Null for a line too short to say, or
     * one whose reading does not fit its box (a misread, a dropped mark).
     */
    private fun emOf(line: GameLine): Double? {
        val units = line.text.sumOf { c -> if (c == '　') 1.0 else if (c.isWhitespace()) 0.0 else if (c.code >= FULL_WIDTH_FROM || c in FULL_WIDTH_MARKS) 1.0 else HALF_WIDTH }
        // One line only: a wrapped utterance joined into one has the
        // characters of all its lines across the width of the longest.
        if (units < MIN_EM_UNITS || line.bounds.height > line.typeHeight * MAX_ONE_LINE_OF_TYPE) return null
        val em = line.bounds.width / units
        val fromHeight = line.typeHeight / GLYPH_OF_EM
        return em.takeIf { it in fromHeight * MIN_EM_OF_HEIGHT..fromHeight * MAX_EM_OF_HEIGHT }
    }

    private fun steadyWeight(label: Boolean, measured: Float): Float {
        val samples = if (label) labelWeights else lineWeights
        if (samples.size < WEIGHT_SAMPLES) samples += measured
        return weightOf(label) ?: measured
    }

    private fun lookOf(frame: Bitmap, recognised: GameLine, others: List<GameLine>, speaker: Boolean): Pair<GameLine, SourceStyle> {
        // The ink, not the recogniser's box, is what has to be covered: a
        // name read while it was still sliding in had a box missing its top,
        // and a line read without its opening 「……」 left the dots showing.
        // Never past halfway to the next piece on its row, though: a short
        // name's patch grew into the affiliation beside it and erased 「玄」.
        val b = recognised.bounds
        // On its own panel only: the dimmed list behind a pop-up stood on the
        // pop-up's rows, and its centred lines were set from the left.
        // Nor the pieces of the line itself: a wrapped block's own lines are
        // on its rows, and a centred pop-up's message was set from the left.
        val beside = (others + iconBoxes.map { GameLine("", it, 0.0) }).filter { it.bounds != b && sameRow(it.bounds, b) && !within(it.bounds, b) }
        val row = beside.filter { sidePanel(frame, it, recognised) }
        // Every piece on the row bounds the patch, on this panel or another:
        // a centred pop-up line's patch grew over the dimmed list behind it.
        val markEnd = colouredMarkInside(frame, b)
        val leftLimit = maxOf(
            beside.filter { it.bounds.right <= b.left }.maxOfOrNull { (it.bounds.right + b.left) / 2 } ?: 0,
            iconBefore(frame, b, recognised.typeHeight) ?: 0,
        )
        val rightLimit = beside.filter { it.bounds.left >= b.right }.minOfOrNull { (it.bounds.left + b.right) / 2 } ?: frame.width
        val extent = inkExtent(frame, b, recognised.typeHeight, leftLimit, rightLimit)
        // Centred on the screen — a choice, a banner — and set again centred,
        // over a box made even about the centre: the ink to one side reached
        // further than the other once a closing 」 stood apart, and was left
        // standing beside a translation set from the left.
        val mid = frame.width / 2
        // Not a line of the dialogue box, which runs from the box's margin
        // and can be centred by chance: a two-line utterance was set centred.
        val onScreenCentre = row.isEmpty() && extent.width < frame.width * MAX_CENTRED_WIDTH &&
            extent.left > frame.width * MIN_CENTRED_LEFT &&
            abs((extent.left + extent.right) / 2 - mid) <= frame.width * CENTRED_TOLERANCE
        // Or in the middle of a plain panel of its own: a reply button in a
        // chat, half the screen from the centre. Set from the left, its longer
        // translation had room on one side only and came out smaller than the
        // option above it.
        val rightLimitEdge = minOf(rightLimit - SIDE_MARGIN_PX - EDGE_SAMPLE_COLUMNS, frame.width)
        val leftLimitEdge = maxOf(leftLimit + SIDE_MARGIN_PX + EDGE_SAMPLE_COLUMNS, 0)
        // Nor a line starting where a name over it starts: dialogue under its
        // speaker. Under 「？？？」, over a figure's coat that ended either side
        // of it, 「お会いできて嬉しいです、先生。」 was taken for a button and
        // its translation set in from the margin.
        val underName = others.any { o ->
            o.bounds != b && GameLayout.speaksAbove(o.bounds, b) && abs(o.bounds.left - b.left) <= recognised.typeHeight
        }
        val panelMiddle = if (onScreenCentre || row.isNotEmpty() || underName) null else {
            val far = (recognised.typeHeight * PANEL_CENTRE_LOOK).toInt()
            val l = plainAfter(frame, extent, leftLimitEdge, far, leftward = true)
            val r = plainAfter(frame, extent, rightLimitEdge, far)
            // Both edges found: a button's panel ends either side. Plain as far
            // as was looked on both sides is an open panel — a story's dialogue
            // box, a short line in it — and a line there is set from the left:
            // centred, 「虚像已无影无踪…」 stood in the middle of the box.
            val reach = far - SIDE_MARGIN_PX - EDGE_SAMPLE_COLUMNS
            if (l >= recognised.typeHeight * PANEL_CENTRE_ROOM && r >= recognised.typeHeight * PANEL_CENTRE_ROOM &&
                abs(l - r) <= recognised.typeHeight && (l < reach || r < reach)
            ) {
                (extent.left + extent.right) / 2
            } else {
                null
            }
        }
        val centred = onScreenCentre || panelMiddle != null
        // Followed across wider gaps too: a choice's quotation marks stand a
        // character and a half off its words, and were left beside them.
        val wide = if (centred) inkExtent(frame, extent, recognised.typeHeight, leftLimit, rightLimit, gapLines = CENTRED_GAP_LINES) else extent
        val centre = panelMiddle ?: mid
        val half = max(centre - wide.left, wide.right - centre)
            .coerceAtMost(min(centre - leftLimit, rightLimit - centre).coerceAtLeast(max(centre - extent.left, extent.right - centre)))
        // Past the end of a line set from the left, the plain panel it stands
        // on is room for a translation a little wider than the original: in a
        // chat's bubble, 「打扫?」 for 「掃除?」 was a few pixels too wide for the
        // original's ink and was shrunk, smaller than every bubble around it.
        val most = (recognised.typeHeight * ROOM_OF_TYPE).toInt()
        val room = if (centred) 0 else plainAfter(frame, extent, minOf(rightLimit - SIDE_MARGIN_PX - EDGE_SAMPLE_COLUMNS, frame.width), most)
        // Centred, the same on both sides: a reply button's 「今天也在冒险中吗?」
        // was two characters wider than 「今日も冒険中?」 on a button with room to
        // spare, and set smaller than the option above it.
        val roomEach = if (!centred) 0 else {
            val probe = TextBounds(centre - half, wide.top, centre + half, wide.bottom, wide.space)
            minOf(
                plainAfter(frame, probe, rightLimitEdge, most),
                plainAfter(frame, probe, leftLimitEdge, most, leftward = true),
            )
        }
        val line = recognised.copy(
            bounds = if (centred) {
                TextBounds(centre - half - roomEach, wide.top, centre + half + roomEach, wide.bottom, wide.space)
            } else {
                TextBounds(max(extent.left, markEnd ?: 0), extent.top, extent.right + room, extent.bottom, extent.space)
            },
        )
        // Room for a line of type the original's height: a typeface's line is
        // taller than its glyphs' ink, and in the recogniser's tight box the
        // translation was shrunk to about 60% of the original to fit.
        val lineRoom = (line.typeHeight * TYPE_LINE_RATIO).toInt() - line.bounds.height
        val rowMargin = maxOf(MIN_ERASE_MARGIN_PX, line.typeHeight / ERASE_MARGIN_DIVISOR, lineRoom / 2 + 2)
        // Over art, painted from its start alone: the panel under a name is
        // where the name begins, and Fate/Grand Order's tab fades into the
        // picture before the end of a long name, where the far end's colour
        // drew a black block behind 「罗曼医生」.
        // With a piece beside it on its row — a name before its affiliation —
        // the gap between the two is still the tab, and both ends can be
        // sampled: painted from the start alone, the tab's fade along a long
        // name was lost and its place showed as a darker block.
        val onArt = line.backdropError > GameTextFilter.MAX_BACKDROP_ERROR && rightLimit >= frame.width
        val backdrop = if (line.backdropError <= MAX_ERASE_ERROR) {
            rowGradient(frame, line.bounds, SIDE_MARGIN_PX, rowMargin, fromStartOnly = onArt)
        } else {
            null
        }
        // Where the panel is known, the ink is whatever stands out of it: a
        // short piece can count more dark outline than light letter, and
        // measured, one cyan 風紀委員会 came back navy.
        val lighterInk = backdrop?.let { luma(it.colorAt(it.rows / 2, 0.5f)) < MID_LUMA }
        val strokes = strokesOf(frame, line.bounds, lighterInk ?: true)
        val single = inkColour(frame, line.bounds, lighterInk)
        val two = if (single != null && line.text.any(Char::isDigit)) twoInks(frame, line.bounds, lighterInk) else null
        return line to SourceStyle(
            foregroundColor = two?.first ?: single,
            accentColor = two?.second,
            orientation = TextOrientation.HORIZONTAL,
            glyphSizePx = steadySize(speaker, line.typeHeight, null),
            backdrop = backdrop,
            outlineColor = strokes.outline,
            // Unmeasured, the weight of its kind: left out, a line came out
            // regular among bold ones of the same type.
            strokeWeight = strokes.weight?.let { steadyWeight(speaker, it / line.typeHeight.coerceAtLeast(1)) }
                ?: weightOf(speaker),
            centred = centred,
        )
    }

    private class Strokes(val outline: Int?, val weight: Float?)

    /**
     * The original's edge and weight, from each row's runs of ink: what lies
     * just either side of a run is outline if it is clearly darker than the
     * panel (lighter, for dark ink), panel otherwise; the runs' typical
     * length is the strokes' width.
     */
    private fun strokesOf(frame: Bitmap, box: TextBounds, lighterInk: Boolean): Strokes {
        val w = box.width
        val h = box.height
        if (w <= 2 * OUTLINE_PROBE_PX || h <= 0) return Strokes(null, null)
        val sign = if (lighterInk) 1.0 else -1.0
        val above = (box.top - 2).coerceAtLeast(0)
        val below = (box.bottom + 1).coerceAtMost(frame.height - 1)
        val outsideTop = IntArray(w).also { frame.getPixels(it, 0, w, box.left, above, w, 1) }
        val outsideBottom = IntArray(w).also { frame.getPixels(it, 0, w, box.left, below, w, 1) }
        val panel = DoubleArray(w) { (luma(outsideTop[it]) + luma(outsideBottom[it])) / 2 }
        val row = IntArray(w)
        val runs = mutableListOf<Int>()
        val edge = mutableListOf<Int>()
        var probes = 0
        for (y in box.top until box.bottom step 2) {
            frame.getPixels(row, 0, w, box.left, y, w, 1)
            fun ink(x: Int) = (luma(row[x]) - panel[x]) * sign >= INK_CONTRAST
            var x = 0
            while (x < w) {
                if (!ink(x)) { x++; continue }
                val start = x
                while (x < w && ink(x)) x++
                runs += x - start
                for (probe in listOf(start - OUTLINE_PROBE_PX, x - 1 + OUTLINE_PROBE_PX)) {
                    if (probe !in 0 until w) continue
                    probes++
                    if ((panel[probe] - luma(row[probe])) * sign >= OUTLINE_CONTRAST) edge += row[probe]
                }
            }
        }
        if (runs.isEmpty()) return Strokes(null, null)
        runs.sort()
        // The mean of the middle half, not the middle run: runs are whole
        // pixels, and at 40 px type the middle one was 5 on one line and 6 on
        // the next — 0.125 or 0.15 of the type, either side of bold, and a
        // chat's bubbles came out in two weights.
        val middleHalf = runs.subList(runs.size / 4, (runs.size * 3 / 4).coerceAtLeast(runs.size / 4 + 1))
        val weight = middleHalf.average().toFloat()
        val outline = if (probes > 0 && edge.size * 2 >= probes) {
            Color.rgb(
                edge.sumOf { Color.red(it) } / edge.size,
                edge.sumOf { Color.green(it) } / edge.size,
                edge.sumOf { Color.blue(it) } / edge.size,
            )
        } else {
            null
        }
        return Strokes(outline, weight)
    }

    /**
     * [box] grown out to the ink just beyond it, by at most [reach] on each
     * side and along the row no further than [leftLimit] and [rightLimit]: a row or column still holding light-on-dark (or dark-on-light)
     * strokes past the edge is part of the line. Stops at the first clear
     * one, so a plain panel adds nothing.
     */
    private fun sameRow(a: TextBounds, b: TextBounds): Boolean =
        minOf(a.bottom, b.bottom) - maxOf(a.top, b.top) > minOf(a.height, b.height) / 2

    private fun inkExtent(
        frame: Bitmap,
        box: TextBounds,
        reach: Int,
        leftLimit: Int,
        rightLimit: Int,
        gapLines: Double = 1.0 / 3,
    ): TextBounds {
        if (box.width <= 0 || box.height <= 0) return box
        fun inked(pixels: IntArray, lighter: Boolean): Boolean {
            val lumas = pixels.map { luma(it) }
            val bg = lumas.sorted()[lumas.size / 2]
            val ink = lumas.map { (if (lighter) it - bg else bg - it) >= INK_CONTRAST }
            // Not what runs in from either end: that is another panel the
            // line's own one stops at, not type. White type in a blue bubble
            // it nearly fills — MomoTalk's 「それはよかった」 — has the white
            // page at the bubble's rounded corners and past its edges, and
            // the patch grew over the whole bubble and painted it white.
            val from = ink.indexOfFirst { !it }.let { if (it < 0) return false else it }
            val to = ink.indexOfLast { !it }
            return (from..to).count { ink[it] } >= EXTENT_MIN_INK
        }
        fun row(y: Int) = IntArray(box.width).also { frame.getPixels(it, 0, box.width, box.left, y, box.width, 1) }
        // Light ink on a dark panel: the middle row's middle value is panel.
        val middle = row(box.top + box.height / 2).map { luma(it) }.sorted()
        val lighter = middle[middle.size / 2] < MID_LUMA
        fun inked(pixels: IntArray) = inked(pixels, lighter)
        fun column(x: Int, top: Int, bottom: Int) =
            IntArray(bottom - top).also { frame.getPixels(it, 0, 1, x, top, 1, bottom - top) }

        // Up across a small gap too: ruby over the line belongs to it, and
        // left standing it read as stray Japanese above the translation. Not
        // as far as the name row, which sits a whole line above.
        var top = box.top
        var y = box.top - 1
        while (y >= 0 && box.top - y <= (reach * RUBY_REACH).toInt()) {
            if (inked(row(y))) top = y else if (top - y > reach / RUBY_GAP_DIVISOR) break
            y--
        }
        var bottom = box.bottom
        while (bottom < frame.height && bottom - box.bottom < reach && inked(row(bottom))) bottom++
        // Along the line, across a gap as well: a 「！」 or 「……」 the recogniser
        // left out stands a little apart from the rest, and missed, the
        // patch's edge was sampled on it and drew a white bar.
        // A third of the type: a name and its affiliation stand further apart.
        val gap = (reach * gapLines).toInt()
        var left = box.left
        // Room left for the patch's edge to be sampled on panel, inside the limits.
        val edge = SIDE_MARGIN_PX + EDGE_SAMPLE_COLUMNS
        var x = box.left - 1
        while (x >= leftLimit + edge && box.left - x <= reach * ALONG_REACH) {
            if (inked(column(x, top, bottom))) left = x else if (left - x > gap) break
            x--
        }
        // Further along than up or down: a 「――」 the recogniser dropped ran
        // 300 px past the end of the line and was left standing.
        var right = box.right
        x = box.right
        while (x < rightLimit - edge && x - box.right < reach * ALONG_REACH) {
            if (inked(column(x, top, bottom))) right = x + 1 else if (x - right >= gap) break
            x++
        }
        // And in from an end the box took in with nothing on it: a left
        // bubble's 「いえ」 had its box start on the bubble's tail and margin,
        // half a character before its words, and the translation was set
        // from there, out past the bubble's edge. Only half a character or
        // more of nothing; a box's usual slack is left alone. And only nothing
        // plain: on a story's translucent box over the art, type the column
        // test did not see stood in what would have been cut.
        fun bare(x: Int): Boolean {
            val c = column(x, top, bottom).map { luma(it) }
            val mid = c.sorted()[c.size / 2]
            // A pixel or two off is the bubble's edge where its tail joins,
            // not type: the shortest stroke crosses more rows than that.
            return c.count { abs(it - mid) > BARE_LUMA } <= BARE_STRAY_PX
        }
        if (left == box.left) {
            var empty = 0
            while (empty < box.width / 3 && bare(box.left + empty)) empty++
            if (empty >= reach / 2) left = box.left + empty - SHRINK_SLACK_PX
        }
        if (right == box.right) {
            var empty = 0
            while (empty < box.width / 3 && bare(box.right - 1 - empty)) empty++
            if (empty >= reach / 2) right = box.right - empty + SHRINK_SLACK_PX
        }
        return TextBounds(left, top, right, bottom, box.space)
    }

    /** How many columns after [box], up to [most] and [limit], are the plain panel at its end. */
    private fun plainAfter(frame: Bitmap, box: TextBounds, limit: Int, most: Int, leftward: Boolean = false): Int {
        val h = box.bottom - box.top
        if (h <= 0 || box.top < 0 || box.bottom > frame.height) return 0
        val column = IntArray(h)
        fun plain(x: Int): Double? {
            frame.getPixels(column, 0, 1, x, box.top, 1, h)
            val lumas = column.map { luma(it) }
            val mid = lumas.sorted()[h / 2]
            return if (lumas.any { abs(it - mid) > BARE_LUMA }) null else mid
        }
        // Columns going out from the box: right of it, or left of it.
        fun at(n: Int) = if (leftward) box.left - 1 - n else box.right + n
        fun inside(n: Int) = if (leftward) at(n) - SIDE_MARGIN_PX - EDGE_SAMPLE_COLUMNS > limit else at(n) + SIDE_MARGIN_PX + EDGE_SAMPLE_COLUMNS < limit
        // From the first plain column: the box can end on the last glyph's
        // softened edge, and 「先生!」 got no room at all past its 「!」.
        var start = 0
        while (start < LEAD_IN_PX && inside(start) && plain(at(start)) == null) start++
        val reference = if (inside(start)) plain(at(start)) ?: return 0 else return 0
        var n = start
        while (n < most && inside(n)) {
            val mid = plain(at(n)) ?: break
            if (abs(mid - reference) > BARE_LUMA) break
            n++
        }
        // Room for the patch's own edge to be sampled still on the panel.
        return (n - SIDE_MARGIN_PX - EDGE_SAMPLE_COLUMNS).coerceAtLeast(0)
    }

    /**
     * Per row of [box] grown by [margin] along it and [rowMargin] up and
     * down, the colour just outside it at each end: the mean of a few
     * columns, so one stray pixel of outline does not colour a whole row.
     * Null where the box runs off the frame.
     */
    private fun rowGradient(frame: Bitmap, box: TextBounds, margin: Int, wantedRowMargin: Int, fromStartOnly: Boolean = false): RowGradient? {
        // Not past the panel the line is on: a bubble the text nearly fills
        // — MomoTalk's 「それはよかった」 — ends within the margin, the rows
        // sampled above and below were the white page, and the bubble was
        // painted out white. Over art the rows outside are art anyway.
        val rowMargin = if (fromStartOnly) wantedRowMargin else onPanelRows(frame, box, wantedRowMargin)
        val top = box.top - rowMargin
        val bottom = box.bottom + rowMargin
        val left = box.left - margin - EDGE_SAMPLE_COLUMNS
        val right = box.right + margin + EDGE_SAMPLE_COLUMNS
        if (top < 0 || bottom > frame.height || left < 0 || right > frame.width) return null
        val rows = bottom - top
        val column = IntArray(rows * EDGE_SAMPLE_COLUMNS)
        fun edge(x: Int): List<Int> {
            frame.getPixels(column, 0, EDGE_SAMPLE_COLUMNS, x, top, EDGE_SAMPLE_COLUMNS, rows)
            return List(rows) { y ->
                var r = 0
                var g = 0
                var b = 0
                for (i in 0 until EDGE_SAMPLE_COLUMNS) {
                    val p = column[y * EDGE_SAMPLE_COLUMNS + i]
                    r += Color.red(p); g += Color.green(p); b += Color.blue(p)
                }
                Color.rgb(r / EDGE_SAMPLE_COLUMNS, g / EDGE_SAMPLE_COLUMNS, b / EDGE_SAMPLE_COLUMNS)
            }
        }
        // The panel per row from above and below the text, not from inside
        // it alone: a pixel font's strokes fill more than half of some rows,
        // and their middle colour was the ink — drawn as lines through the
        // patch. Inside the row only what is near that panel colour counts.
        val above = rowMiddle(frame, box, (top - 1).coerceAtLeast(0))
        val below = rowMiddle(frame, box, (top + rows).coerceAtMost(frame.height - 1))
        // Above and below not one panel — a chat sender's name sits on the
        // page with the bubble just under it — and the blend between them
        // was neither: its lower rows matched the dark type, and the patch
        // came out grey. The one of the two the line itself is mostly on is
        // its panel.
        val band = if (abs(luma(above) - luma(below)) > EDGE_PANEL_LUMA) {
            List(rows) { commoner(frame, box, above, below) }
        } else {
            List(rows) { i -> blend(above, below, i / (rows - 1f).coerceAtLeast(1f)) }
        }
        val inside = insideRows(frame, box, top, rows, near = band, overArt = fromStartOnly)
        if (fromStartOnly) return RowGradient(inside, inside, margin, rowMargin)
        val (l, r) = BackdropEdges.repaired(edge(left), edge(box.right + margin))
        // An end that is not the panel the text sits on — a button's border,
        // a bevel's highlight just outside the box — takes the row's own
        // panel colour instead: Shattered Pixel Dungeon's buttons came out
        // striped where rows of border were interpolated across the patch.
        fun fit(ends: List<Int>) = ends.mapIndexed { i, c ->
            if (abs(luma(c) - luma(inside[i])) > EDGE_PANEL_LUMA) inside[i] else c
        }
        return RowGradient(fit(l), fit(r), margin, rowMargin)
    }

    /**
     * Whether [below] sits on the panel [above] does: the gap between them,
     * over [below]'s width, the colour of [above]'s own row — not the row
     * just under it, which in a bubble the text nearly fills is the page
     * already, as the gap to the home screen's 「ショップ」 was. Only a clearly
     * different panel says no — a translucent box over moving art is not one
     * colour either.
     */
    private fun onePanel(frame: Bitmap, above: GameLine, below: GameLine): Boolean {
        val gapTop = above.bounds.bottom
        val gapBottom = below.bounds.top
        if (gapBottom - gapTop < 2) return true
        val y = ((gapTop + gapBottom) / 2).coerceIn(0, frame.height - 1)
        val under = ((above.bounds.top + above.bounds.bottom) / 2).coerceIn(0, frame.height - 1)
        val left = below.bounds.left.coerceIn(0, frame.width - 1)
        val right = below.bounds.right.coerceIn(left + 1, frame.width)
        val aLeft = above.bounds.left.coerceIn(0, frame.width - 1)
        val aRight = above.bounds.right.coerceIn(aLeft + 1, frame.width)
        fun middle(x0: Int, x1: Int, row: Int): Double {
            val px = IntArray(x1 - x0).also { frame.getPixels(it, 0, it.size, x0, row, it.size, 1) }
            return px.map { luma(it) }.sorted()[px.size / 2]
        }
        // Over the columns both lines share: a narration's first line ran the
        // box's width, its right end over the brighter floor showing through.
        val from = maxOf(left, aLeft)
        val to = minOf(right, aRight)
        // And not only through its middle, which on a dense line of bold
        // type is mostly ink (luma 240 against the panel's 40): its top and
        // bottom rows show the panel too.
        if (to - from >= above.typeHeight * 2) {
            val gap = middle(from, to, y)
            val rows = listOf(under, (above.bounds.top + 2).coerceIn(0, frame.height - 1), (above.bounds.bottom - 2).coerceIn(0, frame.height - 1))
            return rows.any { row -> abs(gap - middle(from, to, row)) <= OTHER_PANEL_LUMA }
        }
        return abs(middle(left, right, y) - middle(aLeft, aRight, under)) <= OTHER_PANEL_LUMA
    }

    private fun within(inner: TextBounds, outer: TextBounds): Boolean =
        inner.left >= outer.left - 2 && inner.right <= outer.right + 2 && inner.top >= outer.top - 2 && inner.bottom <= outer.bottom + 2

    /**
     * The parts of [frame] that changed since the last reading, as boxes with
     * edges on blank panel; null where an edge finds none, and the caller
     * reads the band instead.
     */
    private fun changedBoxes(frame: Bitmap, grid: IntArray): List<IntArray>? {
        val cellWidth = frame.width.toFloat() / GRID_COLUMNS
        val cellHeight = frame.height.toFloat() / GRID_ROWS
        val out = mutableListOf<IntArray>()
        for (region in ChangedBand.regions(lastGrid, grid, GRID_COLUMNS, gap = REGION_GAP_CELLS)) {
            var box = ChangedBand.widenedBox(
                lastLines,
                ((region.columns.first - BAND_MARGIN_CELLS) * cellWidth).toInt(),
                ((region.rows.first - BAND_MARGIN_CELLS) * cellHeight).toInt(),
                ((region.columns.last + 1 + BAND_MARGIN_CELLS) * cellWidth).toInt(),
                ((region.rows.last + 1 + BAND_MARGIN_CELLS) * cellHeight).toInt(),
                marginX = cellWidth.toInt(),
                marginY = cellHeight.toInt() / 2,
                frameWidth = frame.width,
                frameHeight = frame.height,
            )
            // Where an edge finds no blank panel, the part is read as a band
            // the frame's width instead, as a whole change of those rows is.
            box = onBlank(frame, box) ?: run {
                val (t, b) = ChangedBand.widened(lastLines, box[1], box[3], margin = cellHeight.toInt() / 2, frameHeight = frame.height)
                intArrayOf(0, t, frame.width, b)
            }
            out += box
        }
        // Parts that came to overlap are read as one.
        var merged = true
        while (merged) {
            merged = false
            loop@ for (i in out.indices) for (j in i + 1 until out.size) {
                val a = out[i]
                val b = out[j]
                if (a[0] < b[2] && b[0] < a[2] && a[1] < b[3] && b[1] < a[3]) {
                    out[i] = intArrayOf(min(a[0], b[0]), min(a[1], b[1]), max(a[2], b[2]), max(a[3], b[3]))
                    out.removeAt(j)
                    merged = true
                    break@loop
                }
            }
        }
        return out
    }

    /** [box] with each edge moved out to the nearest blank column or row, or null where there is none near. */
    private fun onBlank(frame: Bitmap, box: IntArray): IntArray? {
        var (l, t, r, b) = box.toList()
        val reach = (frame.height * BLANK_REACH_OF_HEIGHT).toInt()
        // Blank is panels, not one colour: a column down a chat list
        // crosses its header, its rows, the selected row's shade — a few
        // long runs. Type is many short ones.
        fun blank(lumas: List<Double>): Boolean {
            var changes = 0
            var run = 0
            var shortRuns = 0
            for (i in 1 until lumas.size) {
                if (abs(lumas[i] - lumas[i - 1]) > BARE_LUMA) {
                    changes++
                    if (run < BLANK_MIN_RUN) shortRuns++
                    run = 0
                } else {
                    run++
                }
            }
            return shortRuns <= BLANK_STRAY && changes <= BLANK_MAX_CHANGES
        }
        fun columnBlank(x: Int): Boolean {
            if (x <= 0 || x >= frame.width - 1) return true
            val c = IntArray(b - t).also { frame.getPixels(it, 0, 1, x, t, 1, b - t) }
            return blank((c.indices step 2).map { luma(c[it]) })
        }
        fun rowBlank(y: Int): Boolean {
            if (y <= 0 || y >= frame.height - 1) return true
            val row = IntArray(r - l).also { frame.getPixels(it, 0, it.size, l, y, it.size, 1) }
            return blank((row.indices step 2).map { luma(row[it]) })
        }
        // An edge with no blank near it is no edge: a speech bubble just
        // appearing over the art was cut where nothing had been read yet, and
        // read as two lines, two translations stacked on each other. The
        // caller reads such a part as a band; moving art is left out by the
        // art mask instead.
        var n = 0
        while (!rowBlank(t) && n++ < reach) t--
        if (n > reach) return null
        n = 0
        while (!rowBlank(b - 1) && n++ < reach) b++
        if (n > reach) return null
        n = 0
        while (!columnBlank(l) && n++ < reach) l--
        if (n > reach) return null
        n = 0
        while (!columnBlank(r - 1) && n++ < reach) r++
        if (n > reach) return null
        return intArrayOf(l.coerceAtLeast(0), t.coerceAtLeast(0), r.coerceAtMost(frame.width), b.coerceAtMost(frame.height))
    }

    /**
     * Whether the row from [fromX] to [line]'s start, at its middle, crosses
     * at most the edge of the line's own bubble: a window's border into the
     * art behind it is more.
     */
    private fun onePage(frame: Bitmap, line: GameLine, fromX: Int): Boolean {
        val from = fromX.coerceIn(0, frame.width - 1)
        val to = line.bounds.left.coerceIn(from, frame.width)
        if (to - from < 4) return true
        val y = ((line.bounds.top + line.bounds.bottom) / 2).coerceIn(0, frame.height - 1)
        val row = IntArray(to - from).also { frame.getPixels(it, 0, it.size, from, y, it.size, 1) }
        var edges = 0
        var last = luma(row[0])
        for (x in SAMPLE_STEP until row.size step SAMPLE_STEP) {
            val l = luma(row[x])
            if (abs(l - last) > SIDE_PANEL_LUMA) edges++
            last = l
        }
        // And the line stands on that page, not past its edge: the page's
        // colour where the row starts is the colour just before the line.
        // and just after it: a badge past the window's edge had the page up
        // to its left side and the art beyond its right.
        val page = luma(row[0])
        val before = luma(row[(row.size - 1 - SAMPLE_STEP * 2).coerceAtLeast(0)])
        val afterX = (line.bounds.right + line.bounds.height / 2).coerceAtMost(frame.width - 1)
        val after = luma(frame.getPixel(afterX, y))
        return edges <= PAGE_EDGES && abs(before - page) <= SIDE_PANEL_LUMA && abs(after - page) <= SIDE_PANEL_LUMA
    }

    /** Whether nothing in [box] is bright: a dimmer over it. */
    private fun dimmed(frame: Bitmap, box: TextBounds): Boolean = brightestOf(frame, box) < DIMMED_BRIGHTEST

    private fun brightestOf(frame: Bitmap, box: TextBounds): Double {
        val l = box.left.coerceIn(0, frame.width - 1)
        val r = box.right.coerceIn(l + 1, frame.width)
        val t = box.top.coerceIn(0, frame.height - 1)
        val b = box.bottom.coerceIn(t + 1, frame.height)
        val row = IntArray(r - l)
        val lumas = ArrayList<Double>()
        for (y in t until b step 2) {
            frame.getPixels(row, 0, row.size, l, y, row.size, 1)
            for (x in row.indices step 2) lumas += luma(row[x])
        }
        lumas.sort()
        return lumas[(lumas.size * 95) / 100]
    }

    /** The spread of brightness in [box], ink to panel: low where a dimmer lies over the line. */
    private fun contrastOf(frame: Bitmap, box: TextBounds): Double {
        val l = box.left.coerceIn(0, frame.width - 1)
        val r = box.right.coerceIn(l + 1, frame.width)
        val t = box.top.coerceIn(0, frame.height - 1)
        val b = box.bottom.coerceIn(t + 1, frame.height)
        val row = IntArray(r - l)
        val lumas = ArrayList<Double>()
        for (y in t until b step 2) {
            frame.getPixels(row, 0, row.size, l, y, row.size, 1)
            for (x in row.indices step 2) lumas += luma(row[x])
        }
        lumas.sort()
        return lumas[(lumas.size * 95) / 100] - lumas[lumas.size / 20]
    }

    /** Whether the space between two lines on one row is the panel either stands on. */
    private fun sidePanel(frame: Bitmap, a: GameLine, b: GameLine): Boolean {
        val (l, r) = if (a.bounds.left <= b.bounds.left) a to b else b to a
        val from = l.bounds.right.coerceIn(0, frame.width - 1)
        val to = r.bounds.left.coerceIn(from, frame.width)
        if (to - from < 4) return true
        val y = ((max(a.bounds.top, b.bounds.top) + min(a.bounds.bottom, b.bounds.bottom)) / 2).coerceIn(0, frame.height - 1)
        val gap = IntArray(to - from).also { frame.getPixels(it, 0, it.size, from, y, it.size, 1) }.map { luma(it) }.sorted()
        fun own(line: GameLine): Double {
            val x0 = line.bounds.left.coerceIn(0, frame.width - 1)
            val x1 = line.bounds.right.coerceIn(x0 + 1, frame.width)
            val row = IntArray(x1 - x0).also { frame.getPixels(it, 0, it.size, x0, y, it.size, 1) }
            return row.map { luma(it) }.sorted()[row.size / 2]
        }
        val middle = gap[gap.size / 2]
        // Closer than another panel: a dimmed list behind a pop-up is the
        // pop-up's own colour darkened by a half, not by a whole panel's step.
        // And no edge between them: a pop-up's border and its shadow lay
        // between its line and the white reply button behind it, alike in
        // colour on either side.
        val edges = gap.count { abs(it - middle) > SIDE_PANEL_LUMA }
        return abs(middle - own(a)) <= SIDE_PANEL_LUMA && abs(middle - own(b)) <= SIDE_PANEL_LUMA && edges * 20 < gap.size
    }

    /** How many of [wanted] rows above and below [box] are still the panel its middle row is on. */
    private fun onPanelRows(frame: Bitmap, box: TextBounds, wanted: Int): Int {
        if (box.top - wanted - 1 < 0 || box.bottom + wanted + 1 > frame.height) return wanted
        val own = luma(rowMiddle(frame, box, box.top + box.height / 2))
        // Another panel, not a shade of this one: Blue Archive's dialogue
        // box darkens toward its foot, and cut at the first shade its patches
        // lost a third of their height.
        fun panel(y: Int) = abs(luma(rowMiddle(frame, box, y)) - own) <= OTHER_PANEL_LUMA
        var up = 0
        while (up < wanted && panel(box.top - up - 2)) up++
        var down = 0
        while (down < wanted && panel(box.bottom + down + 1)) down++
        return min(up, down)
    }

    /** The panel's brightness just above and just below [box]. */
    private fun panelAround(frame: Bitmap, box: TextBounds): Pair<Double, Double> {
        val clipped = TextBounds(
            box.left.coerceIn(0, frame.width - 1),
            box.top,
            box.right.coerceIn(box.left.coerceIn(0, frame.width - 1) + 1, frame.width),
            box.bottom,
            box.space,
        )
        return luma(rowMiddle(frame, clipped, (box.top - 1).coerceIn(0, frame.height - 1))) to
            luma(rowMiddle(frame, clipped, box.bottom.coerceIn(0, frame.height - 1)))
    }

    private fun repainted(was: Pair<Double, Double>, now: Pair<Double, Double>): Boolean =
        // Above and below both: a shade laid over a row changes the two. The
        // list's last row stands at the window's foot over the home screen's
        // animation, and was painted again every frame for its lower edge.
        abs(was.first - now.first) > PANEL_CHANGED_LUMA && abs(was.second - now.second) > PANEL_CHANGED_LUMA

    private fun rowMiddle(frame: Bitmap, box: TextBounds, y: Int): Int {
        val row = IntArray(box.width)
        frame.getPixels(row, 0, box.width, box.left, y, box.width, 1)
        return (0 until box.width step SAMPLE_STEP).map { row[it] }.sortedBy { luma(it) }.let { it[it.size / 2] }
    }

    /** Whichever of [a] and [b] more of [box]'s pixels are near. */
    private fun commoner(frame: Bitmap, box: TextBounds, a: Int, b: Int): Int {
        val row = IntArray(box.width)
        var nearA = 0
        var nearB = 0
        for (y in box.top until box.bottom step SAMPLE_STEP) {
            frame.getPixels(row, 0, box.width, box.left, y, box.width, 1)
            for (x in 0 until box.width step SAMPLE_STEP) {
                val l = luma(row[x])
                if (abs(l - luma(a)) <= EDGE_PANEL_LUMA) nearA++
                if (abs(l - luma(b)) <= EDGE_PANEL_LUMA) nearB++
            }
        }
        return if (nearA >= nearB) a else b
    }

    private fun blend(a: Int, b: Int, t: Float): Int = Color.rgb(
        (Color.red(a) + (Color.red(b) - Color.red(a)) * t).toInt(),
        (Color.green(a) + (Color.green(b) - Color.green(a)) * t).toInt(),
        (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t).toInt(),
    )

    /**
     * Each row's own panel colour, from inside the line: the middle colour of
     * the row by brightness, which is panel wherever the type is the
     * minority. Over art the ends of a line are not panel — a name's tab
     * fades into the picture, and its pointed end is dark — while what lies
     * between the glyphs is.
     */
    private fun insideRows(frame: Bitmap, box: TextBounds, top: Int, rows: Int, near: List<Int>, overArt: Boolean): List<Int> {
        val w = box.width
        val row = IntArray(w)
        val all = List(rows) { i ->
            val y = (top + i).coerceIn(0, frame.height - 1)
            frame.getPixels(row, 0, w, box.left, y, w, 1)
            val sampled = (0 until w step SAMPLE_STEP).map { row[it] }.sortedBy { luma(it) }
            val panelLike = sampled.filter { abs(luma(it) - luma(near[i])) <= EDGE_PANEL_LUMA }
            // Too little of the row near the band — art behind a name's tab —
            // and the row's own middle colour is the better guess.
            // Over art the rows outside the line are art too, and say nothing
            // about the tab under a name: there, the row alone decides.
            when {
                !overArt && panelLike.size * 4 >= sampled.size -> panelLike[panelLike.size / 2]
                // Over art, but most of the row is the colour above and below
                // it: a chat sender's name on the white page beside an avatar
                // scored as art, and its patch came out grey.
                overArt && panelLike.size * 2 >= sampled.size -> panelLike[panelLike.size / 2]
                // Over art, the middle of what is not type: the middle of a row
                // full of yellow type was yellow, and was drawn as lines
                // through the patch.
                overArt -> {
                    // The row without its ink: everything near the row's
                    // lightest (light type) or darkest pixel left out.
                    val light = luma(near[i]) < MID_LUMA
                    val extreme = luma(if (light) sampled.last() else sampled.first())
                    val rest = sampled.filter { abs(luma(it) - extreme) > INK_CONTRAST / 2 }
                    if (rest.isEmpty()) sampled[sampled.size / 2] else rest[rest.size / 2]
                }
                // A row the type fills: the panel above and below is the panel.
                else -> near[i]
            }
        }
        return all
    }

    /**
     * The colour of the line's ink: its strongest pixels on the line's own
     * side of the backdrop. The mean of all of them came out grey — a glyph's
     * anti-aliased edge and a letter still fading in pull it toward the panel.
     */
    private fun inkColour(frame: Bitmap, box: TextBounds, lighterInk: Boolean?): Int? {
        val w = box.width
        val h = box.height
        if (w <= 0 || h <= 0) return null
        val pixels = IntArray(w * h)
        frame.getPixels(pixels, 0, w, box.left, box.top, w, h)
        val column = DoubleArray(h)
        var lighter = 0
        var darker = 0
        val light = mutableListOf<Int>()
        val dark = mutableListOf<Int>()
        for (x in 0 until w step 2) {
            for (y in 0 until h) column[y] = luma(pixels[y * w + x])
            val bg = column.sortedArray()[h / 2]
            for (y in 0 until h) {
                val d = column[y] - bg
                if (d >= INK_CONTRAST) { lighter++; light += pixels[y * w + x] }
                else if (-d >= INK_CONTRAST) { darker++; dark += pixels[y * w + x] }
            }
        }
        val useLight = lighterInk ?: (lighter >= darker)
        val ink = if (useLight) light else dark
        if (ink.isEmpty()) return null
        val sorted = ink.sortedBy { luma(it) }
        val core = if (useLight) sorted.takeLast(max(1, sorted.size * INK_CORE_PERCENT / 100))
        else sorted.take(max(1, sorted.size * INK_CORE_PERCENT / 100))
        return Color.rgb(
            core.sumOf { Color.red(it) } / core.size,
            core.sumOf { Color.green(it) } / core.size,
            core.sumOf { Color.blue(it) } / core.size,
        )
    }

    /**
     * The line's own ink and a second one, when a line with figures has two:
     * a skill's description sets its figures in blue among grey words, and
     * its translation was set in one bluish grey throughout. The ink pixels
     * are split in two by colour; two inks far apart, the lesser a fair share
     * of the ink, are the words' and the figures'. Null for one ink.
     */
    private fun twoInks(frame: Bitmap, box: TextBounds, lighterInk: Boolean?): Pair<Int, Int>? {
        val w = box.width
        val h = box.height
        if (w <= 0 || h <= 0 || box.left < 0 || box.top < 0 || box.right > frame.width || box.bottom > frame.height) return null
        val pixels = IntArray(w * h)
        frame.getPixels(pixels, 0, w, box.left, box.top, w, h)
        val column = DoubleArray(h)
        val ink = mutableListOf<Int>()
        for (x in 0 until w step 2) {
            for (y in 0 until h) column[y] = luma(pixels[y * w + x])
            val bg = column.sortedArray()[h / 2]
            for (y in 0 until h) {
                val d = column[y] - bg
                if ((lighterInk == true && d >= INK_CONTRAST) || (lighterInk != true && -d >= INK_CONTRAST)) ink += pixels[y * w + x]
            }
        }
        if (ink.size < MIN_TWO_INK_PIXELS) return null
        fun chroma(p: Int) = maxOf(Color.red(p), Color.green(p), Color.blue(p)) - minOf(Color.red(p), Color.green(p), Color.blue(p))
        // Two means, from the least and the most colourful pixel.
        var a = ink.minByOrNull(::chroma)!!
        var b = ink.maxByOrNull(::chroma)!!
        var inA: List<Int> = ink
        var inB = emptyList<Int>()
        repeat(6) {
            val (nearA, nearB) = ink.partition { colourDistance(it, a) <= colourDistance(it, b) }
            if (nearA.isEmpty() || nearB.isEmpty()) return null
            inA = nearA
            inB = nearB
            a = mean(nearA)
            b = mean(nearB)
        }
        val (words, figures) = if (inA.size >= inB.size) inA to inB else inB to inA
        if (figures.size * 100 < ink.size * MIN_ACCENT_PERCENT || colourDistance(mean(words), mean(figures)) <= ACCENT_DISTANCE) return null
        // Each ink by its strongest pixels, as [inkColour] takes one: the
        // mean of a glyph's anti-aliased edge is lighter than its stroke, and
        // set in it the words came out pale and were given an outline.
        fun core(pixels: List<Int>): Int {
            val sorted = pixels.sortedBy { luma(it) }
            val n = max(1, sorted.size * INK_CORE_PERCENT / 100)
            return mean(if (lighterInk == true) sorted.takeLast(n) else sorted.take(n))
        }
        return core(words) to core(figures)
    }

    private fun mean(pixels: List<Int>): Int =
        Color.rgb(pixels.sumOf { Color.red(it) } / pixels.size, pixels.sumOf { Color.green(it) } / pixels.size, pixels.sumOf { Color.blue(it) } / pixels.size)

    private fun colourDistance(a: Int, b: Int): Double {
        val dr = (Color.red(a) - Color.red(b)).toDouble()
        val dg = (Color.green(a) - Color.green(b)).toDouble()
        val db = (Color.blue(a) - Color.blue(b)).toDouble()
        return kotlin.math.sqrt(dr * dr + dg * dg + db * db)
    }

    private fun toElement(
        key: String,
        line: GameLine,
        style: SourceStyle,
        conversational: Boolean,
        window: TextBounds,
        packageName: String,
        speaker: Boolean = false,
        row: List<String> = emptyList(),
    ): TextElement {
        val b = line.bounds
        val grow = style.backdrop?.marginPx ?: 0
        val growRows = style.backdrop?.rowMarginPx ?: 0
        return TextElement(
            id = TextElementId("game:${key.hashCode().toUInt().toString(16)}"),
            text = line.text,
            // Grown by the erase margin when there is one, so the cleared
            // patch takes in the glyphs' outline.
            bounds = TextBounds(
                b.left - grow + window.left,
                b.top - growRows + window.top,
                b.right + grow + window.left,
                b.bottom + growRows + window.top,
                CoordinateSpace.SCREEN,
            ),
            sourceType = TextSourceType.GAME,
            // Dialogue carries the lines before it as context; a label does
            // not. A menu's 「エピソードリスト」, given the story's lines as
            // context, came back as the chapter's title.
            conversational = conversational,
            source = SourceIdentity(packageName = packageName),
            revision = Revision(session),
            sourceLanguage = JAPANESE.takeIf {
                JapaneseScript.isJapanese(line.text) ||
                    (kanaVotes >= JAPANESE_GAME_KANA && JapaneseScript.couldBeJapanese(line.text))
            },
            style = style,
            // A name over dialogue is sent as a name: alone, 「ヒナ」 came back
            // as 「雏」 ("chick") where beside its affiliation it was 「日奈」.
            // And the game it is from, so names and terms come back as that
            // game's players know them: 「チナツ」, alone, was 「千夏」 one day
            // and 「奇娜」 the next.
            metadata = buildMap {
                put(com.babel.core.model.TranslationRequest.Hints.GAME, packageName)
                if (speaker) put(com.babel.core.model.TranslationRequest.Hints.SPEAKER, "true")
                if (row.isNotEmpty()) put(com.babel.core.model.TranslationRequest.Hints.ROW, row.joinToString("\n"))
            },
        )
    }

    private companion object {
        const val TAG = "GameText"
        const val SAMPLE_STEP = 4

        /** Only a line several times wider than tall can hold two pieces. */
        const val MIN_SPLIT_ASPECT = 4
        const val MIN_PIECE_PX = 24
        const val MIN_RECOGNISABLE_PX = 32

        /** Labels whose type is at most this share of the frame's height are small; see [withRowGapsRead]. */
        const val SMALL_LABEL_OF_HEIGHT = 0.024
        const val LABEL_INK_DISTANCE = 60.0
        const val MIN_ICON_PX = 6
        const val BAR_GAP_PX = 4

        /** Elements of one recognised line this far apart, against their height, are two pieces of text. */
        const val WORD_GAP_OF_HEIGHT = 0.6
        const val SYMBOL_GAP_OF_HEIGHT = 0.7
        const val WORD_PIECE_GAP = 0.4
        const val MAX_ROW_STYLES = 16
        /** Inks of one row this close (0–441) are one ink: 466398 against 496f8f measured 22. */
        const val ROW_INK_DISTANCE = 45.0

        /** Two pictures this close in time can say a line stood still; the platform takes one every ~350 ms. */
        const val STILL_PAIR_MS = 800L

        /** Ink this far (0–441) from the line's own is a second ink: blue figures against grey words measured over 150. */
        const val ACCENT_DISTANCE = 110.0

        /** And this share of the line's ink, so a glyph's anti-aliased edge is not one. */
        const val MIN_ACCENT_PERCENT = 5
        const val MIN_TWO_INK_PIXELS = 60

        /** Colour distance (0–441) from the panel that makes a column a coloured mark: the cyan bar on white measured 128. */
        const val MARK_COLOUR = 90.0
        const val PARTICLES = "をがにはのでとへも"

        /**
         * Ink colours (0–441) this far apart make a first glyph a badge: the
         * heart against a white name measured 64–175, a name's own first
         * letter against the next 6–41. A kana is then confirmed by reading
         * the badge's number.
         */
        const val BADGE_COLOUR = 55.0

        /** Where Japanese script starts in Unicode (CJK punctuation, kana, Han). */
        const val JAPANESE_FROM = 0x3000

        /** Quotes and brackets: they open a line of text, and are no badge. */
        const val OPENING_MARKS = "\"'“‘(（[［<〈《【"

        /** Marks a word cannot begin with; a reading that does lost its first letter. */
        const val LOST_START_MARKS = "-ー一―‐"

        /**
         * A change of type within a line: across a gap of this much of its
         * height, the smaller type this share of the larger. A skill's label
         * against the skill's name measured 0.40–0.41; a speaker's katakana
         * name against the kanji of a smaller affiliation 0.75–0.78, katakana
         * so much shorter than kanji that size alone does not tell them apart.
         */
        const val TYPE_CHANGE_GAP_OF_HEIGHT = 0.25
        const val MIN_TYPE_CHANGE = 0.3
        const val MAX_TYPE_CHANGE = 0.7
        const val TYPE_HEIGHT_PERCENTILE = 0.8
        const val TYPE_CHANGE_REACH_OF_HEIGHT = 2.5

        /**
         * Ink colours (0–441) this far apart along with a change of size: the
         * skill label's blue-grey against the name's dark grey measured 40–47,
         * one stretch of a guide's text against the next 31.
         */
        const val TYPE_CHANGE_COLOUR = 36.0

        /** A speaker's name takes at most this much of its line: チナツ took 45%, レイジョ 61%, 「玄龍門の構成員」 beside 「玄龍門」 over 75%. */
        const val MAX_NAME_FRACTION = 0.85
        const val SPLIT_CACHE = 64
        const val REREAD_CACHE = 512

        /**
         * The most backdrop error a line may have and still be erased: the
         * interpolation that measures it is the one that paints it back.
         */
        const val MAX_ERASE_ERROR = GameTextFilter.MAX_BACKDROP_ERROR * 3

        /**
         * A line of type against its glyphs' ink: text size 1/0.88 of it, and
         * a line of CJK type — the fallback font sets the line's height —
         * about 1.45 of that.
         */
        const val TYPE_LINE_RATIO = 1.45 / 0.88

        /**
         * Clear space along the line, beyond its ink: the ink's extent already
         * covers the glyphs, and more reached into the piece beside it.
         */
        const val SIDE_MARGIN_PX = 2

        /** Clear space above and below the text, against the type's height; at least a few pixels. */
        const val ERASE_MARGIN_DIVISOR = 8
        const val MIN_ERASE_MARGIN_PX = 4

        /** How far outside a stroke its outline is looked for, and how far from the panel it must be. */
        const val OUTLINE_PROBE_PX = 2
        const val OUTLINE_CONTRAST = 25.0

        /** The share of ink pixels, strongest first, that give the ink its colour. */
        const val INK_CORE_PERCENT = 30

        /** A line's look: this grid, and how far (0..255) any cell may change and be the same. */
        const val LOOK_COLUMNS = 24
        const val LOOK_ROWS = 4
        const val REPLACED_LOOK_LUMA = 16.0
        const val MAX_TRACK_LINES = 4
        const val SHIFT_TRIES = 3
        const val TRACK_STEP_PX = 4
        const val STILL_LOOK_LUMA = 12.0

        /** A line with this many times less ink than one over it is behind it. */
        const val BEHIND_RATIO = 3

        /** Ink, per thousand samples, that means a line is still there; a line of type has 50-200. */
        const val MIN_INK_PER_MILLE = 8

        /** A line whose middle is this close (of the width) to the screen's is centred, if not too wide. */
        const val CENTRED_TOLERANCE = 0.02
        const val MAX_CENTRED_WIDTH = 0.6
        const val MIN_CENTRED_LEFT = 0.2
        const val CENTRED_GAP_LINES = 2.0


        /** The renderer's glyph height against the type's size (`ErasedTextView`'s GLYPH_TO_TEXT_SIZE). */
        const val GLYPH_OF_EM = 0.88
        const val FULL_WIDTH_FROM = 0x2E80
        const val FULL_WIDTH_MARKS = "…‥―—"
        const val HALF_WIDTH = 0.55
        const val MIN_EM_UNITS = 2.0
        const val MIN_EM_OF_HEIGHT = 0.75
        const val MAX_EM_OF_HEIGHT = 1.15
        const val MAX_EM_SAMPLES = 32
        const val MAX_ONE_LINE_OF_TYPE = 1.6

        /** Type heights this close are one size of type, measured on different letters. */
        const val SAME_TYPE_RATIO = 0.86

        /** How many lines of a kind its weight is taken from. */
        const val WEIGHT_SAMPLES = 12

        /** Places remembered where a speaker's name has stood. */
        const val MAX_SPEAKER_SPOTS = 4

        /** The least of its one-line box a line's type fills. */
        const val MIN_TYPE_OF_BOX = 0.67

        /** Kana, decayed over recent frames, that make a game a Japanese one. */
        const val JAPANESE_GAME_KANA = 20.0

        /** Middle of 0..255 brightness: a panel below it carries light ink. */
        const val MID_LUMA = 128.0

        /** How far above a line, in type heights, ruby is looked for, and the gap it may leave (1/n). */
        const val RUBY_REACH = 0.6
        const val RUBY_GAP_DIVISOR = 4

        /** How much further, in type heights, the ink is followed along a line than across it. */
        const val ALONG_REACH = 8

        /** Ink pixels that make a row or column beyond the box part of the line. */
        const val EXTENT_MIN_INK = 2

        /** How far (0..255) an end's colour may stand from the row's panel and still be panel. */
        const val EDGE_PANEL_LUMA = 30.0

        /** A panel this far from the line's own is a different one: a bubble's page. */
        const val OTHER_PANEL_LUMA = 80.0
        const val SIDE_PANEL_LUMA = 40.0

        /** The most backdrop error a label translated before may have and still be translated; see [readOne]. */
        const val KNOWN_LABEL_MAX_BACKDROP = 28.0

        /** Ink against its panel at least this far apart (0..255) is in full view; dimmed, it measured 22–55, bright 150 and more. */
        const val ACTIVE_CONTRAST = 100.0

        /** A line whose brightest is under this is behind a dimmer: dimmed white measured 44–83. */
        const val DIMMED_BRIGHTEST = 120.0
        const val PAGE_EDGES = 2

        /** What is kept of an empty end a box is cut back from. */
        const val SHRINK_SLACK_PX = 2
        const val BARE_LUMA = 10.0
        const val BARE_STRAY_PX = 2

        /** How far past a box's end its last glyph's edge may still reach. */
        const val LEAD_IN_PX = 6

        /** How much plain panel past a line's end may be taken as room, against its type's height. */
        const val ROOM_OF_TYPE = 1.0

        /** How far, in type heights, plain panel is looked for either side, and how much each side needs to be the panel's middle. */
        const val PANEL_CENTRE_LOOK = 4.0
        const val PANEL_CENTRE_ROOM = 2.0

        /** How much of the line's end, against its height, a last mark is looked for in: about one narrow glyph. */
        const val TAIL_WIDTH_OF_HEIGHT = 0.4
        const val TAIL_MARKS = "?？>〉"

        /**
         * Less type than this in the last mark's width, and the mark was not
         * there. Measured: a tail read as a mark 0.2%; a real 「？」 the box
         * ended inside of 0.7–10%, and at 5% 「バグ？」 lost its question.
         */
        const val TAIL_MAX_INK = 0.01

        /** More ink than this in the last mark's width is a filled button; a 「？」 glyph is mostly panel. */
        const val BUTTON_INK = 0.5

        /** How far the panel under a painted-out line may change before it is painted again. */
        const val PANEL_CHANGED_LUMA = 10.0


        /** Columns averaged at each end of a row for its colour. */
        const val EDGE_SAMPLE_COLUMNS = 3

        /** A row is inside the type when it holds this fraction (1/n) of the busiest row's ink. */
        const val TYPE_ROW_SHARE = 40

        /** Empty rows allowed inside one line of type; ruby sits further off. */
        const val TYPE_ROW_GAP = 2

        /** Brightness apart from the column's middle value that counts as ink. */
        const val INK_CONTRAST = 70.0

        /** About 50 px a cell on a 3200x1440 window: a dialogue line is a row or two. */
        const val GRID_COLUMNS = 64
        const val GRID_ROWS = 29

        /** A cell of slack above and below, so a line's edge is not cut off. */
        const val BAND_MARGIN_CELLS = 1

        /**
         * How long a place a conversation was in stays a place for dialogue:
         * long enough for a story's silences, short enough that a menu shown
         * after the story is not read as one.
         */
        const val CONVERSATION_KEPT_MS = 60_000L
        const val MAX_CONVERSATION_PLACES = 12

        /** How much of the script vote each frame keeps from the last: about the last 50 frames count. */
        const val SCRIPT_VOTE_DECAY = 0.98

        /** Mean brightness change per grid cell, 0..255, that is a new scene. */
        const val SCENE_CHANGE_LUMA = 30.0


        const val MAX_SENDERS = 32

        /** The small copy of each picture, at this fraction, and how far its line may move and be still. */
        const val SMALL_SCALE = 4

        /** How often a picture is taken to watch for translations to withdraw, at most. */
        const val WATCH_INTERVAL_MS = 1000L
        const val WATCH_POLL_MS = 30L

        /** How long a word offered for translation stays known: a still screen's readings take turns within it. */
        const val OFFERED_KEPT_MS = 30_000L

        /** How often what game mode spent is logged. */
        const val DUTY_EVERY_MS = 60_000L

        /** The kinds of reading [Duty] counts, as `readOne` names them. */
        val KINDS = listOf("unchanged", "parts", "band", "whole")
        fun kindOf(how: String): Int = when {
            how == "unchanged" -> 0
            how.endsWith("%") -> 1
            how.startsWith("band") -> 2
            else -> 3
        }

        /** No ink for this much of the type's height inside a label is between two of them. */
        const val INK_GAP_OF_TYPE = 1.0
        const val MIN_GAP_SPLIT_LETTERS = 5

        /**
         * Reading parts: below this share of the frame in all; parts this
         * many grid cells apart are separate; an edge is moved at most this
         * share of the frame's height to find blank panel, and blank allows
         * this many stray samples.
         */
        const val PART_AREA_FRACTION = 0.45

        const val REGION_GAP_CELLS = 3
        const val BLANK_REACH_OF_HEIGHT = 0.08
        const val BLANK_STRAY = 1
        const val BLANK_MIN_RUN = 4
        const val BLANK_MAX_CHANGES = 24
        const val STILL_SMALL_LUMA = 2.0

        /** How wide each piece of a dialogue line's room is compared for typing, in its type's heights. */
        const val TYPING_PIECE_OF_HEIGHT = 2

        /** How long readings stay whole while dialogue types out: as long as `StableLines` holds a typing line back. */
        const val TYPING_WHOLE_MS = 4000L

        /** How far before a chat column's edge a message's patch starts. */
        const val COLUMN_START_SLACK_PX = 2


        /** A band this tall is a new screen, not a new line: read it whole. */
        const val WHOLE_FRAME_FRACTION = 0.5f
        val JAPANESE = LanguageTag("ja")
    }
}
