package com.babel.platform.game

import android.graphics.Bitmap
import android.graphics.Color
import com.babel.core.common.BabelLogger
import com.babel.core.common.Redact
import com.babel.core.model.CoordinateSpace
import com.babel.core.model.LanguageTag
import com.babel.core.model.Revision
import com.babel.core.model.SourceIdentity
import com.babel.core.model.TextBounds
import com.babel.core.model.TextElement
import com.babel.core.model.TextElementId
import com.babel.core.model.TextSourceType
import com.babel.domain.acquisition.TextSourceEvent
import com.babel.domain.game.ChangedBand
import com.babel.domain.game.GameLayout
import com.babel.domain.game.GameLine
import com.babel.domain.game.GameTextFilter
import com.babel.domain.game.GameTextScanner
import com.babel.domain.game.StableLines
import com.babel.domain.vision.JapaneseScript
import com.babel.platform.screen.ScreenFrameSource
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlin.math.max
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
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
) : GameTextScanner {

    override val sourceType: TextSourceType = TextSourceType.GAME

    private val events = MutableSharedFlow<TextSourceEvent>(extraBufferCapacity = 16)

    override fun events(): Flow<TextSourceEvent> = events.asSharedFlow()

    private val lock = Mutex()
    private var lines = StableLines()

    /**
     * Bumped when the screen is cleared. A conversation's lines share one, so
     * the translator's dialogue context carries across them (`PageContext`
     * pages by revision).
     */
    private var session = 0L
    private var published = mutableMapOf<String, TextElementId>()

    private val recognizer by lazy {
        TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
    }

    /** The grid the last read frame was compared on, and what was read from it. */
    private var lastGrid: IntArray? = null
    private var lastLines: List<GameLine> = emptyList()

    override suspend fun read(
        windowId: Int,
        windowBounds: TextBounds,
        packageName: String,
        stillWanted: () -> Boolean,
    ) = coroutineScope {
        // The next picture is taken while this one is read. The frame source
        // waits out the platform's interval itself, so this asks as soon as it
        // may and no sooner.
        var next = async { frames.windowFrame(windowId) }
        while (stillWanted()) {
            val frame = next.await()
            if (!stillWanted()) {
                frame?.recycle()
                break
            }
            next = async { frames.windowFrame(windowId) }
            if (frame == null) continue
            try {
                readOne(frame, windowBounds, packageName)
            } finally {
                frame.recycle()
            }
        }
        next.cancel()
        runCatching { next.await()?.recycle() }
        Unit
    }

    private suspend fun readOne(frame: Bitmap, windowBounds: TextBounds, packageName: String) {
        val started = System.currentTimeMillis()
        val grid = gridOf(frame)
        val band = ChangedBand.of(lastGrid, grid, GRID_COLUMNS)

        val read: List<GameLine>
        val how: String
        if (band == null) {
            // Nothing moved: the last reading stands, and is offered again so
            // that two frames agreeing still settles a line.
            read = lastLines
            how = "unchanged"
        } else {
            val cellHeight = frame.height.toFloat() / GRID_ROWS
            val top = ((band.first - BAND_MARGIN_CELLS) * cellHeight).toInt().coerceAtLeast(0)
            val bottom = ((band.last + 1 + BAND_MARGIN_CELLS) * cellHeight).toInt().coerceAtMost(frame.height)
            read = if (bottom - top >= frame.height * WHOLE_FRAME_FRACTION) {
                how = "whole"
                recognise(frame, 0, frame.height)
            } else {
                how = "band ${bottom - top}px"
                ChangedBand.merge(lastLines, recognise(frame, top, bottom), top, bottom)
            }
            lastGrid = grid
            lastLines = read
        }
        val readMs = System.currentTimeMillis() - started

        // Speaker labels kept with their dialogue, wrapped lines joined into
        // one utterance (`GameLayout`).
        val kept = GameLayout.joinWrapped(GameLayout.select(read, frame.height))

        lock.withLock {
            val update = lines.next(kept, now = System.currentTimeMillis())
            val gone = update.gone.mapNotNull { published.remove(it) }
            if (gone.isNotEmpty()) events.emit(TextSourceEvent.Removed(gone))

            val upserted = update.settled.map { settled ->
                val element = toElement(settled.key, settled.line, windowBounds, packageName)
                published[settled.key] = element.id
                logger.debug(
                    TAG,
                    "settled ${Redact.text(settled.line.text)} after ${settled.settledAfterMs}ms",
                )
                element
            }
            if (upserted.isNotEmpty()) events.emit(TextSourceEvent.Upserted(upserted))
        }
        logger.debug(TAG, "read $how: ${read.size} lines, kept ${kept.size}, in ${readMs}ms")
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
            lines = StableLines()
            lastGrid = null
            lastLines = emptyList()
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
    private suspend fun recognise(frame: Bitmap, top: Int, bottom: Int): List<GameLine> = try {
        val part = if (top == 0 && bottom == frame.height) frame else Bitmap.createBitmap(frame, 0, top, frame.width, bottom - top)
        val result = try {
            recognizer.process(InputImage.fromBitmap(part, 0)).await()
        } finally {
            if (part !== frame) part.recycle()
        }
        val width = frame.width
        val height = frame.height
        result.textBlocks.flatMap { it.lines }.mapNotNull { line ->
            val box = line.boundingBox ?: return@mapNotNull null
            if (line.text.isBlank()) return@mapNotNull null
            val bounds = TextBounds(box.left, box.top + top, box.right, box.bottom + top, CoordinateSpace.SCREEN)
            GameLine(line.text, bounds, backdropError(frame, width, height, bounds))
        }.flatMap { splitByColour(frame, it) }
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (failure: Throwable) {
        logger.warn(TAG, "recognition failed", failure)
        emptyList()
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
            val l = luma(pixels[row + left])
            val r = luma(pixels[row + right])
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

    /** What a line was split into last time, so a still line is not re-read every frame. */
    private val splits = object : LinkedHashMap<String, List<GameLine>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<GameLine>>?) = size > SPLIT_CACHE
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

        val cuts = GameLayout.splitPoints(inkRuns(frame, b), b.height)
        val pieces = if (cuts.isEmpty()) {
            listOf(line)
        } else {
            val edges = listOf(b.left) + cuts + listOf(b.right)
            val read = edges.zipWithNext().mapNotNull { (l, r) ->
                if (r - l < MIN_PIECE_PX) return@mapNotNull null
                val crop = Bitmap.createBitmap(frame, l, b.top, r - l, b.height)
                val text = try {
                    recognizer.process(InputImage.fromBitmap(crop, 0)).await().text.replace("\n", "").trim()
                } finally {
                    crop.recycle()
                }
                if (text.isBlank()) null else GameLine(text, TextBounds(l, b.top, r, b.bottom, b.space), line.backdropError)
            }
            // A piece that read as nothing means the cut was wrong: keep the whole.
            if (read.size == edges.size - 1) read else listOf(line)
        }
        splits[key] = pieces
        return pieces
    }

    /** Runs of inked columns across [box], each with its mean ink colour. */
    private fun inkRuns(frame: Bitmap, box: TextBounds): List<GameLayout.InkRun> {
        val w = box.width
        val h = box.height
        if (w <= 0 || h <= 0) return emptyList()
        val pixels = IntArray(w * h)
        frame.getPixels(pixels, 0, w, box.left, box.top, w, h)
        val runs = mutableListOf<GameLayout.InkRun>()
        var start = -1
        var r = 0L
        var g = 0L
        var bl = 0L
        var n = 0L
        val column = DoubleArray(h)
        fun close(end: Int) {
            if (start >= 0 && n > 0) {
                runs += GameLayout.InkRun(box.left + start, box.left + end, (r / n).toInt(), (g / n).toInt(), (bl / n).toInt(), n.toInt())
            }
            start = -1; r = 0; g = 0; bl = 0; n = 0
        }
        for (x in 0 until w) {
            for (y in 0 until h) column[y] = luma(pixels[y * w + x])
            val background = column.sortedArray()[h / 2]
            var inked = false
            for (y in 0 until h) {
                if (abs(column[y] - background) >= INK_CONTRAST) {
                    val p = pixels[y * w + x]
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

    private fun toElement(key: String, line: GameLine, window: TextBounds, packageName: String): TextElement {
        val b = line.bounds
        return TextElement(
            id = TextElementId("game:${key.hashCode().toUInt().toString(16)}"),
            text = line.text,
            bounds = TextBounds(
                b.left + window.left,
                b.top + window.top,
                b.right + window.left,
                b.bottom + window.top,
                CoordinateSpace.SCREEN,
            ),
            sourceType = TextSourceType.GAME,
            conversational = true,
            source = SourceIdentity(packageName = packageName),
            revision = Revision(session),
            sourceLanguage = JAPANESE.takeIf { JapaneseScript.isJapanese(line.text) },
        )
    }

    private companion object {
        const val TAG = "GameText"
        const val SAMPLE_STEP = 4

        /** Only a line several times wider than tall can hold two pieces. */
        const val MIN_SPLIT_ASPECT = 4
        const val MIN_PIECE_PX = 24
        const val SPLIT_CACHE = 64

        /** Brightness apart from the column's middle value that counts as ink. */
        const val INK_CONTRAST = 70.0

        /** About 50 px a cell on a 3200x1440 window: a dialogue line is a row or two. */
        const val GRID_COLUMNS = 64
        const val GRID_ROWS = 29

        /** A cell of slack above and below, so a line's edge is not cut off. */
        const val BAND_MARGIN_CELLS = 1

        /** A band this tall is a new screen, not a new line: read it whole. */
        const val WHOLE_FRAME_FRACTION = 0.5f
        val JAPANESE = LanguageTag("ja")
    }
}
