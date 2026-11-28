package com.babel.platform.game

import android.graphics.Bitmap
import android.graphics.Color
import android.os.SystemClock
import com.babel.core.common.BabelLogger
import com.babel.core.common.Redact
import com.babel.core.model.CoordinateSpace
import com.babel.core.model.SourceStyle
import com.babel.core.model.TextBounds
import com.babel.core.model.TextElement
import com.babel.core.model.TextElementId
import com.babel.core.model.TextOrientation
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
import com.babel.domain.game.GameLineStyle.BADGE_COLOUR
import com.babel.domain.game.GameLineStyle.SIDE_MARGIN_PX
import com.babel.domain.game.GameLineStyle.bar
import com.babel.domain.game.GameLineStyle.colouredMarkInside
import com.babel.domain.game.GameLineStyle.iconBefore
import com.babel.domain.game.GameLineStyle.inkColour
import com.babel.domain.game.GameLineStyle.inkExtent
import com.babel.domain.game.GameLineStyle.inkRuns
import com.babel.domain.game.GameLineStyle.inkedColumns
import com.babel.domain.game.GameLineStyle.plainAfter
import com.babel.domain.game.GameLineStyle.sameRow
import com.babel.domain.game.GameLineStyle.strokesOf
import com.babel.domain.game.GameLineStyle.twoInks
import com.babel.domain.game.GameLineStyle.typeHeight
import com.babel.domain.game.GamePanelSampler.BAND_MARGIN_CELLS
import com.babel.domain.game.GamePanelSampler.BARE_LUMA
import com.babel.domain.game.GamePanelSampler.EDGE_SAMPLE_COLUMNS
import com.babel.domain.game.GamePanelSampler.MID_LUMA
import com.babel.domain.game.GamePanelSampler.OTHER_PANEL_LUMA
import com.babel.domain.game.GamePanelSampler.REGION_GAP_CELLS
import com.babel.domain.game.GamePanelSampler.backdropError
import com.babel.domain.game.GamePanelSampler.backdropOf
import com.babel.domain.game.GamePanelSampler.changedBoxes
import com.babel.domain.game.GamePanelSampler.onItsPanel
import com.babel.domain.game.GamePanelSampler.panelAround
import com.babel.domain.game.GamePanelSampler.panelsOf
import com.babel.domain.game.GamePanelSampler.repainted
import com.babel.domain.game.GamePanelSampler.rowGradient
import com.babel.domain.game.GamePanelSampler.sidePanel
import com.babel.domain.game.GamePixels.colourDistance
import com.babel.domain.game.GamePixels.luma
import com.babel.domain.game.GameTextFilter
import com.babel.domain.game.GameTextScanner
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

    /** One row of labels' box and weight, as first set; see `elementOf` in [readOne]. */
    private class RowStyle(val top: Int, val bottom: Int, val weight: Float?, val ink: Int?)
    private val rowStyles = ArrayDeque<RowStyle>()

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
        val pixels = BitmapFramePixels(frame)
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
            val unbarred = if (text.length >= 3 && text[0] == '一' && colouredMarkInside(pixels, read) != null) text.drop(1) else text
            val (unmarked, unmarkedBox) = withoutLeadingIcon(frame, GameLayout.withKatakanaRestored(GameLayout.withoutStrayStroke(unbarred)), onItsPanel(pixels, read))
            val (kept, bounds) = withoutTail(frame, unmarked, unmarkedBox)
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
        val pixels = BitmapFramePixels(frame)
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
        val row = beside.filter { sidePanel(pixels, it, recognised) }
        // Every piece on the row bounds the patch, on this panel or another:
        // a centred pop-up line's patch grew over the dimmed list behind it.
        val markEnd = colouredMarkInside(pixels, b)
        val leftLimit = maxOf(
            beside.filter { it.bounds.right <= b.left }.maxOfOrNull { (it.bounds.right + b.left) / 2 } ?: 0,
            iconBefore(pixels, b, recognised.typeHeight) ?: 0,
        )
        val rightLimit = beside.filter { it.bounds.left >= b.right }.minOfOrNull { (it.bounds.left + b.right) / 2 } ?: frame.width
        val extent = inkExtent(pixels, b, recognised.typeHeight, leftLimit, rightLimit)
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
            val l = plainAfter(pixels, extent, leftLimitEdge, far, leftward = true)
            val r = plainAfter(pixels, extent, rightLimitEdge, far)
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
        val wide = if (centred) inkExtent(pixels, extent, recognised.typeHeight, leftLimit, rightLimit, gapLines = CENTRED_GAP_LINES) else extent
        val centre = panelMiddle ?: mid
        val half = max(centre - wide.left, wide.right - centre)
            .coerceAtMost(min(centre - leftLimit, rightLimit - centre).coerceAtLeast(max(centre - extent.left, extent.right - centre)))
        // Past the end of a line set from the left, the plain panel it stands
        // on is room for a translation a little wider than the original: in a
        // chat's bubble, 「打扫?」 for 「掃除?」 was a few pixels too wide for the
        // original's ink and was shrunk, smaller than every bubble around it.
        val most = (recognised.typeHeight * ROOM_OF_TYPE).toInt()
        val room = if (centred) 0 else plainAfter(pixels, extent, minOf(rightLimit - SIDE_MARGIN_PX - EDGE_SAMPLE_COLUMNS, frame.width), most)
        // Centred, the same on both sides: a reply button's 「今天也在冒险中吗?」
        // was two characters wider than 「今日も冒険中?」 on a button with room to
        // spare, and set smaller than the option above it.
        val roomEach = if (!centred) 0 else {
            val probe = TextBounds(centre - half, wide.top, centre + half, wide.bottom, wide.space)
            minOf(
                plainAfter(pixels, probe, rightLimitEdge, most),
                plainAfter(pixels, probe, leftLimitEdge, most, leftward = true),
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
            rowGradient(pixels, line.bounds, SIDE_MARGIN_PX, rowMargin, fromStartOnly = onArt)
        } else {
            null
        }
        // Where the panel is known, the ink is whatever stands out of it: a
        // short piece can count more dark outline than light letter, and
        // measured, one cyan 風紀委員会 came back navy.
        val lighterInk = backdrop?.let { luma(it.colorAt(it.rows / 2, 0.5f)) < MID_LUMA }
        val strokes = strokesOf(pixels, line.bounds, lighterInk ?: true)
        val single = inkColour(pixels, line.bounds, lighterInk)
        val two = if (single != null && line.text.any(Char::isDigit)) twoInks(pixels, line.bounds, lighterInk) else null
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

    private fun within(inner: TextBounds, outer: TextBounds): Boolean =
        inner.left >= outer.left - 2 && inner.right <= outer.right + 2 && inner.top >= outer.top - 2 && inner.bottom <= outer.bottom + 2

    private companion object {
        const val TAG = "GameText"

        /** Only a line several times wider than tall can hold two pieces. */
        const val MIN_SPLIT_ASPECT = 4
        const val MIN_PIECE_PX = 24
        const val MIN_RECOGNISABLE_PX = 32

        /** Labels whose type is at most this share of the frame's height are small; see [withRowGapsRead]. */
        const val SMALL_LABEL_OF_HEIGHT = 0.024
        const val LABEL_INK_DISTANCE = 60.0

        /** Elements of one recognised line this far apart, against their height, are two pieces of text. */
        const val WORD_GAP_OF_HEIGHT = 0.6
        const val SYMBOL_GAP_OF_HEIGHT = 0.7
        const val WORD_PIECE_GAP = 0.4
        const val MAX_ROW_STYLES = 16
        /** Inks of one row this close (0–441) are one ink: 466398 against 496f8f measured 22. */
        const val ROW_INK_DISTANCE = 45.0

        /** Two pictures this close in time can say a line stood still; the platform takes one every ~350 ms. */
        const val STILL_PAIR_MS = 800L

        const val PARTICLES = "をがにはのでとへも"

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

        /** Clear space above and below the text, against the type's height; at least a few pixels. */
        const val ERASE_MARGIN_DIVISOR = 8
        const val MIN_ERASE_MARGIN_PX = 4

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

        /** The most backdrop error a label translated before may have and still be translated; see [readOne]. */
        const val KNOWN_LABEL_MAX_BACKDROP = 28.0

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
