package com.babel.platform.game

import android.graphics.Bitmap
import android.graphics.Color
import android.os.SystemClock
import com.babel.core.common.BabelLogger
import com.babel.core.common.Redact
import com.babel.core.model.CoordinateSpace
import com.babel.core.model.TextBounds
import com.babel.core.model.TextElement
import com.babel.core.model.TextElementId
import com.babel.core.model.TextSourceType
import com.babel.domain.acquisition.TextSourceEvent
import com.babel.domain.game.CapturePace
import com.babel.domain.game.ChangedBand
import com.babel.domain.game.GameElements
import com.babel.domain.game.GameFrameLook
import com.babel.domain.game.GameFrameLook.ACTIVE_CONTRAST
import com.babel.domain.game.GameFrameLook.GRID_COLUMNS
import com.babel.domain.game.GameFrameLook.GRID_ROWS
import com.babel.domain.game.GameFrameLook.INK_CONTRAST
import com.babel.domain.game.GameFrameLook.STILL_LOOK_LUMA
import com.babel.domain.game.GameFrameLook.contrastOf
import com.babel.domain.game.GameFrameLook.covers
import com.babel.domain.game.GameFrameLook.dimmed
import com.babel.domain.game.GameFrameLook.foundAt
import com.babel.domain.game.GameFrameLook.frontLayer
import com.babel.domain.game.GameFrameLook.inked
import com.babel.domain.game.GameFrameLook.largestDifference
import com.babel.domain.game.GameFrameLook.lookDifference
import com.babel.domain.game.GameFrameLook.lumaGridOf
import com.babel.domain.game.GameFrameLook.replaced
import com.babel.domain.game.GameFrameLook.replacedSince
import com.babel.domain.game.GameFrameLook.sceneChanged
import com.babel.domain.game.GameFrameLook.sharedShift
import com.babel.domain.game.GameFrameLook.smallOf
import com.babel.domain.game.GameFrameLook.stillBetween
import com.babel.domain.game.GameFrameLook.stillPieceByPiece
import com.babel.domain.game.GameLayout
import com.babel.domain.game.GameLine
import com.babel.domain.game.GameLineRefiner.Companion.JAPANESE_FROM
import com.babel.domain.game.GameLineRefiner.Companion.atDividers
import com.babel.domain.game.GameLineRefiner.Companion.atIcons
import com.babel.domain.game.GameLineRefiner.Companion.atInkGaps
import com.babel.domain.game.GameLineRefiner.Companion.isIcon
import com.babel.domain.game.GameLineRefiner.Companion.separated
import com.babel.domain.game.GameLineRefiner.Companion.withBracketedParts
import com.babel.domain.game.GameLineRefiner.Companion.withWordPiecesJoined
import com.babel.domain.game.GameLineRefiner.Companion.withoutTail
import com.babel.domain.game.GameLineStyle.BADGE_COLOUR
import com.babel.domain.game.GameLineStyle.SIDE_MARGIN_PX
import com.babel.domain.game.GameLineStyle.bar
import com.babel.domain.game.GameLineStyle.colouredMarkInside
import com.babel.domain.game.GameLineStyle.inkColour
import com.babel.domain.game.GameLineStyle.inkRuns
import com.babel.domain.game.GameLineStyle.inkedColumns
import com.babel.domain.game.GameLineStyle.sameRow
import com.babel.domain.game.GameLineStyle.typeHeight
import com.babel.domain.game.GamePanelSampler.BAND_MARGIN_CELLS
import com.babel.domain.game.GamePanelSampler.REGION_GAP_CELLS
import com.babel.domain.game.GamePanelSampler.backdropError
import com.babel.domain.game.GamePanelSampler.backdropOf
import com.babel.domain.game.GamePanelSampler.changedBoxes
import com.babel.domain.game.GamePanelSampler.onItsPanel
import com.babel.domain.game.GamePanelSampler.panelAround
import com.babel.domain.game.GamePanelSampler.panelsOf
import com.babel.domain.game.GamePanelSampler.repainted
import com.babel.domain.game.GamePixels.luma
import com.babel.domain.game.GameStyleMemory
import com.babel.domain.game.GameStyleMemory.Companion.SAME_TYPE_RATIO
import com.babel.domain.game.GameTextFilter
import com.babel.domain.game.GameTextScanner
import com.babel.domain.game.RawElement
import com.babel.domain.game.RawRecognisedLine
import com.babel.domain.game.RawSymbol
import com.babel.domain.game.SmallFrame
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
 * Every step is one of the spikes' findings (`docs/notes/v3.md`):
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
            var before: SmallFrame? = null
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
                val small = smallOf(BitmapFramePixels(frame))
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
     * measure for heat and battery (`docs/notes/v3.md`, criterion 6).
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

    private val styleMemory = GameStyleMemory()

    /** Whether the recogniser is at work on a picture; see [read]. */
    @Volatile
    private var reading = false

    /** The small copy of the newest picture taken, read or not. */
    @Volatile
    private var newest: SmallFrame? = null

    /** A picture, a small copy of its brightness, and the small copy of the picture taken before it. */
    private class Shot(val frame: Bitmap, val small: SmallFrame, val before: SmallFrame?)

    private suspend fun readOne(
        frame: Bitmap,
        windowBounds: TextBounds,
        packageName: String,
        stillOnScreen: (GameLine) -> Boolean = { false },
        stillInPieces: (TextBounds, Int) -> Boolean? = { _, _ -> null },
        unchangedSince: (TextBounds) -> Boolean = { true },
    ) {
        val started = System.currentTimeMillis()
        val pixels = BitmapFramePixels(frame)
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
        val sceneChanged = sceneChanged(lastGrid, grid)


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
            val boxes = if (wholeDue) null else changedBoxes(pixels, lastGrid, lastLines, seen)
            val boxArea = boxes?.sumOf { (it[2] - it[0]).toLong() * (it[3] - it[1]) } ?: Long.MAX_VALUE
            val fresh = if (boxes != null && boxes.isNotEmpty() && boxArea < frame.width.toLong() * frame.height * PART_AREA_FRACTION) {
                how = "${boxes.size} parts, ${boxArea * 100 / (frame.width.toLong() * frame.height)}%"
                var merged = lastLines
                for (box in boxes) {
                    val (l, t, r, b) = box.toList()
                    merged = ChangedBand.mergeBox(merged, recognise(frame, t, b, l, r), l, t, r, b)
                }
                readParts = boxes
                frontLayer(pixels, merged)
            } else if (bottom - top >= frame.height * WHOLE_FRAME_FRACTION || wholeDue) {
                how = "whole"
                // The art mask outlives a whole reading taken because much
                // changed; only the periodic one, which ignores it, renews it.
                if (wholeDue) {
                    lastWholeAt = nowMs
                    artUntil.fill(0L)
                }
                frontLayer(pixels, recognise(frame, 0, frame.height))
            } else {
                how = "band ${bottom - top}px"
                frontLayer(pixels, ChangedBand.merge(lastLines, recognise(frame, top, bottom), top, bottom))
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
        val panels = panelsOf(pixels)
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
                contrastOf(pixels, line.bounds) >= ACTIVE_CONTRAST
        }.toSet()
        // Nothing behind a dimmer, dialogue or label: a pop-up darkens the
        // screen under it, and the student's name and role there were read
        // as a sentence and a reply and drawn over the dark — at the pop-up's
        // side, the player not looking at them. Dimmed, nothing in the line
        // is bright: neither its ink nor its panel.
        val shown = (kept.filter { it in dialogue } + labels.filter { l -> kept.none { it.bounds == l.bounds && it in dialogue } })
            .filter { unchangedSince(it.bounds) && !dimmed(pixels, it.bounds) }
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
                        dimmed(pixels, k.bounds) -> "dimmed"
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
                inked(pixels, gone.bounds) && read.none { otherWords(it.text, gone.text) && overlaps(it.bounds, gone.bounds) } &&
                    // Nor where its pixels are no longer what they were when
                    // it was published: a chat scrolled, other bubbles moved
                    // into its place, nothing read there matched it, and its
                    // translation stood over them for four frames — 3 s.
                    !replacedSince(pixels, publishedLooks, gone.bounds)
            }, heldStill = { key ->
                publishedLooks[key]?.let { (box, then) -> GameFrameLook.lookOf(pixels, box)?.let { now -> lookDifference(then, now) <= STILL_LOOK_LUMA } } == true
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
                val (inked, look0) = styleMemory.lookOf(pixels, line, read, iconBoxes, speaker)
                val look = styleMemory.withRowStyle(read0, seen, conversational, speaker, inked, look0)
                val siblings = if (conversational || speaker) emptyList() else shown.filter { o ->
                    o !== read0 && o !in dialogue && GameLayout.isLabel(o.text) && sameRow(o.bounds, read0.bounds)
                }.sortedBy { it.bounds.left }.map { it.text }
                val element = GameElements.toElement(key, inked, look, conversational, windowBounds, packageName, session, kanaVotes, speaker = speaker, row = siblings)
                roles[element.id] = speaker
                GameFrameLook.lookOf(pixels, line.bounds)?.let { publishedLooks[key] = line.bounds to it }
                if (element.style.backdrop != null) {
                    erased[key] = Erased(line, conversational, speaker, panelAround(pixels, line.bounds))
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
                    repainted(e.panel, panelAround(pixels, e.line.bounds))
            }.map { (key, e) -> elementOf(key, e.line, e.conversational, e.speaker) }
            if (repainted.isNotEmpty()) {
                logger.debug(TAG, "repainted " + repainted.joinToString { "${it.id.value} weight=${it.style.strokeWeight?.let { w -> "%.2f".format(w) }}" })
            }
            // Every size settling now is seen before any is given out: lines
            // settle together, and the first set its size before the tallest
            // of its kind had been measured.
            update.settled.forEach { styleMemory.steadySize(it.line in speakers, it.line.typeHeight, styleMemory.emOf(it.line)) }
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
                val w = roles[e.id]?.let(styleMemory::weightOf)
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
     * A line whose pixels have not changed keeps its last reading. Over
     * moving art the recogniser reads the same still line differently from
     * frame to frame, and behind a translucent history screen it took in the
     * dimmed dialogue box or did not — six readings of one entry, each
     * withdrawing the last. Where the line's own pixels are the same as they
     * were, so is its text; only a line whose pixels changed — typed, scrolled,
     * replaced — is read again.
     */
    private fun keepUnchanged(frame: Bitmap, fresh: List<GameLine>): List<GameLine> {
        val pixels = BitmapFramePixels(frame)
        val looks = HashMap<GameLine, IntArray>()
        val still = lastLines.filter { old ->
            val before = lastLooks[old] ?: return@filter false
            val now = GameFrameLook.lookOf(pixels, old.bounds) ?: return@filter false
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
        for (line in kept) if (line !in looks) GameFrameLook.lookOf(pixels, line.bounds)?.let { looks[line] = it }
        lastLooks = looks
        return kept
    }

    /**
     * Withdraws, before reading the frame, every published line whose place
     * now shows something else. Read first, a scrolled chat's translations
     * stood over the bubbles that had moved in for as long as the recogniser
     * took — half a second to a second and a half a frame.
     */
    private suspend fun withdrawReplaced(frame: Bitmap) {
        val pixels = BitmapFramePixels(frame)
        val keys = lock.withLock {
            // Only lines painted out on a plain panel: over moving art a still
            // line's place changes under it, and would be withdrawn and drawn
            // again frame after frame.
            val changed = publishedLooks.filter { (key, look) ->
                val painted = erased[key] ?: return@filter false
                if (painted.line.backdropError > GameTextFilter.MAX_BACKDROP_ERROR) return@filter false
                val now = GameFrameLook.lookOf(pixels, look.first)
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
            val dy = sharedShift(pixels, publishedLooks, changed)
            val follows = if (dy == null) emptyList() else changed.filter { foundAt(pixels, publishedLooks, it, dy) }
            if (dy != null && follows.isNotEmpty()) {
                val moved = follows.mapNotNull { key ->
                    val (box, _) = publishedLooks[key] ?: return@mapNotNull null
                    val shifted = TextBounds(box.left, box.top + dy, box.right, box.bottom + dy, box.space)
                    val element = shownElements[key] ?: return@mapNotNull null
                    val look = GameFrameLook.lookOf(pixels, shifted) ?: return@mapNotNull null
                    publishedLooks[key] = shifted to look
                    erased[key]?.let { e -> erased[key] = Erased(e.line.copy(bounds = shifted), e.conversational, e.speaker, panelAround(pixels, shifted)) }
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

    /** Mostly different characters: another line, not the same one read a little differently. */
    private fun otherWords(a: String, b: String): Boolean {
        val shared = a.toSet().intersect(b.toSet()).size
        return shared * 2 < minOf(a.toSet().size, b.toSet().size)
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
        return lumaGridOf(pixels)
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
            styleMemory.clear()
            otherScript = null
            latinVotes = 0.0
            kanaVotes = 0.0
            otherVotes = 0.0
            lastSelected = emptyList()
            conversation.clear()
            chatColumns.clear()
            senders.clear()
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
        val pixels = BitmapFramePixels(frame)
        fun onFrame(box: android.graphics.Rect) = TextBounds(box.left + left, box.top + top, box.right + left, box.bottom + top, CoordinateSpace.SCREEN)
        fun rawLine(line: com.google.mlkit.vision.text.Text.Line) = RawRecognisedLine(
            text = line.text,
            bounds = line.boundingBox?.let(::onFrame),
            elements = line.elements.map { e ->
                RawElement(
                    text = e.text,
                    bounds = e.boundingBox?.let(::onFrame),
                    symbols = e.symbols.map { s -> RawSymbol(s.text, s.boundingBox?.let(::onFrame)) },
                )
            },
        )
        val lines = result.textBlocks.flatMap { it.lines }.map(::rawLine).flatMap { line ->
            // Never a sentence: a highlighted word in one is another colour,
            // and with it a little taller or shorter (「イベント」 in a guide).
            // Nor one with figures: a skill's description sets its numbers in
            // blue and larger, and 「…攻撃速度を30.6%増加…」 was cut before them.
            line.bounds?.takeIf { line.text.none { c -> c in GameLayout.SENTENCE_MARKS || c.isDigit() } }?.let { atTypeChange(frame, it) }
                ?: separated(line)
        }.mapNotNull { (text, read) ->
            if (text.isBlank()) return@mapNotNull null
            // A title's accent bar read as 「一」 before a kanji: 「一技術革命+」.
            val unbarred = if (text.length >= 3 && text[0] == '一' && colouredMarkInside(pixels, read) != null) text.drop(1) else text
            val (unmarked, unmarkedBox) = withoutLeadingIcon(frame, GameLayout.withKatakanaRestored(GameLayout.withoutStrayStroke(unbarred)), onItsPanel(pixels, read))
            val (kept, bounds) = withoutTail(pixels, unmarked, unmarkedBox)
            if (kept.isBlank()) return@mapNotNull null
            GameLine(kept, bounds, backdropError(pixels, width, height, bounds), typeHeight(pixels, bounds))
        }
        // Only a speaker's row is cut by colour: a name and its affiliation
        // sit over a line of dialogue. A label over a progress bar has its
        // halves on different colours of bar, and 「ダウンロード中」 came back
        // cut in two and translated as "down" and "loading".
        val around = lines + lastLines
        lines.flatMap { line ->
            if (around.any { it !== line && GameLayout.speaksAbove(line.bounds, it.bounds) }) splitByColour(frame, line) else listOf(line)
        }.flatMap(::atDividers).flatMap { atInkGaps(pixels, it) }.flatMap { atIcons(pixels, it) }.map { line ->
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
                val colour = inkColour(BitmapFramePixels(frame), row[i].bounds.left, row[i].bounds.top, row[i].bounds.right, row[i].bounds.bottom) ?: continue
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
                added += GameLine(text, extent, backdropError(BitmapFramePixels(frame), frame.width, frame.height, extent), line.typeHeight)
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
        val first = inkColour(BitmapFramePixels(frame), box.left, box.top, box.left + glyph, box.bottom) ?: return null
        // Against the next two glyphs, not the whole rest: a name and the
        // affiliation after it in another colour were one line, and the name's
        // first letter was the colour of neither.
        val rest = inkColour(BitmapFramePixels(frame), box.left + glyph, box.top, min(box.right, box.left + 3 * glyph), box.bottom) ?: return null
        val d = kotlin.math.sqrt((0..2).sumOf { (first[it] - rest[it]) * (first[it] - rest[it]) })
        if (d <= BADGE_COLOUR) return null
        // A bar in another colour before a title — a pop-up's accent — is a
        // few columns of ink and a gap before the word: the title's patch
        // painted it out. Its width, and no letter lost.
        bar(BitmapFramePixels(frame), box)?.let { return -it }
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
                inkedColumns(BitmapFramePixels(frame), box.left, box.top, box.left + glyph, box.bottom) * 2 >= glyph -> -glyph
            else -> null
        }
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
        val cuts = GameLayout.splitPoints(inkRuns(BitmapFramePixels(frame), b), b.height)
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
                    GameLine(text, pieceBounds, line.backdropError, typeHeight(BitmapFramePixels(frame), pieceBounds))
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
        canvas.drawColor(backdropOf(BitmapFramePixels(piece)))
        canvas.drawBitmap(piece, ((w - piece.width) / 2).toFloat(), ((h - piece.height) / 2).toFloat(), null)
        piece.recycle()
        return out
    }

    private companion object {
        const val TAG = "GameText"

        /** Only a line several times wider than tall can hold two pieces. */
        const val MIN_SPLIT_ASPECT = 4
        const val MIN_PIECE_PX = 24
        const val MIN_RECOGNISABLE_PX = 32

        /** Labels whose type is at most this share of the frame's height are small; see [withRowGapsRead]. */
        const val SMALL_LABEL_OF_HEIGHT = 0.024
        const val LABEL_INK_DISTANCE = 60.0

        /** Two pictures this close in time can say a line stood still; the platform takes one every ~350 ms. */
        const val STILL_PAIR_MS = 800L

        const val PARTICLES = "をがにはのでとへも"

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

        /** Places remembered where a speaker's name has stood. */
        const val MAX_SPEAKER_SPOTS = 4

        /** The most backdrop error a label translated before may have and still be translated; see [readOne]. */
        const val KNOWN_LABEL_MAX_BACKDROP = 28.0

        /**
         * How long a place a conversation was in stays a place for dialogue:
         * long enough for a story's silences, short enough that a menu shown
         * after the story is not read as one.
         */
        const val CONVERSATION_KEPT_MS = 60_000L
        const val MAX_CONVERSATION_PLACES = 12

        /** How much of the script vote each frame keeps from the last: about the last 50 frames count. */
        const val SCRIPT_VOTE_DECAY = 0.98


        const val MAX_SENDERS = 32

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

        /**
         * Reading parts: below this share of the frame in all; parts this
         * many grid cells apart are separate; an edge is moved at most this
         * share of the frame's height to find blank panel, and blank allows
         * this many stray samples.
         */
        const val PART_AREA_FRACTION = 0.45

        /** How wide each piece of a dialogue line's room is compared for typing, in its type's heights. */
        const val TYPING_PIECE_OF_HEIGHT = 2

        /** How long readings stay whole while dialogue types out: as long as `StableLines` holds a typing line back. */
        const val TYPING_WHOLE_MS = 4000L

        /** How far before a chat column's edge a message's patch starts. */
        const val COLUMN_START_SLACK_PX = 2


        /** A band this tall is a new screen, not a new line: read it whole. */
        const val WHOLE_FRAME_FRACTION = 0.5f
    }
}
