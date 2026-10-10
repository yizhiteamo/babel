package com.babel.platform.game

import android.graphics.Bitmap
import android.graphics.Color
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
    private var otherScript: Boolean? = null
    private var lastSelected: List<GameLine> = emptyList()

    /** How much of the recent frames changed whole, smoothed; see [BUSY_SCENE]. */
    private var motion = 0.0
    private var wasBusy = false

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
        // Which script the game is written in, remembered for the frames that
        // show too little text to tell (`GameLayout.controls`).
        GameLayout.writtenInOtherScript(read)?.let { otherScript = it }
        val selected = GameLayout.select(read, frame.height, frame.width, otherScript, before = lastSelected)
        lastSelected = selected
        val kept = GameLayout.joinWrapped(GameLayout.joinRows(selected, GameLayout.dialogue(selected)))

        val dialogue = GameLayout.dialogue(kept)
        // On a scene that never holds still — a battle — only what reads as
        // dialogue is translated: an instruction, a line someone speaks. Its
        // labels (a unit's class, an enemy's name, a skill) are read at a
        // glance and change under the player's finger, and translating them
        // was more noise than help. A still screen, a menu, keeps them all.
        motion = motion * (1 - MOTION_SMOOTHING) + (if (how == "whole") MOTION_SMOOTHING else 0.0)
        val busy = motion > BUSY_SCENE
        val shown = if (busy) kept.filter { it in dialogue } else kept
        if (busy != wasBusy) {
            wasBusy = busy
            logger.debug(TAG, if (busy) "busy scene: dialogue only" else "still scene: everything")
        }
        lock.withLock {
            val update = lines.next(shown, now = System.currentTimeMillis(), sceneChanged = sceneChanged)
            val gone = update.gone.mapNotNull { published.remove(it) }
            if (gone.isNotEmpty()) events.emit(TextSourceEvent.Removed(gone))
            if (update.gone.isNotEmpty()) {
                // Where and why, never what: the key's text is screen text.
                logger.debug(TAG, "withdrawn " + update.gone.joinToString { "${it.substringAfterLast('@')} ${update.why[it]}" })
            }

            val upserted = update.settled.map { settled ->
                // Every line read, not only those kept: a name left untranslated
                // still has ink an affiliation's patch must not grow into.
                val (inked, look) = lookOf(frame, settled.line, read)
                val element = toElement(settled.key, inked, look, settled.line in dialogue, windowBounds, packageName)
                published[settled.key] = element.id
                logger.debug(
                    TAG,
                    "settled ${element.id.value} ${Redact.text(settled.line.text)}@${settled.line.bounds.left},${settled.line.bounds.top}," +
                        "${settled.line.bounds.width}x${settled.line.bounds.height} type=${settled.line.typeHeight} " +
                        "${if (element.style.backdrop != null) "erased" else "boxed"} " +
                        "outline=${element.style.outlineColor != null} weight=${element.style.strokeWeight?.let { "%.2f".format(it) }} " +
                        "after ${settled.settledAfterMs}ms",
                )
                element
            }
            if (upserted.isNotEmpty()) events.emit(TextSourceEvent.Upserted(upserted))
        }
        logger.debug(TAG, "read $how: ${read.size} lines, kept ${kept.size}, in ${readMs}ms")
        // What was left out and why, as numbers: where a line was, how tall,
        // how busy behind it. Never its words.
        val left = read.filter { line -> selected.none { it === line } }
        if (left.isNotEmpty()) {
            logger.debug(
                TAG,
                "left out " + left.joinToString { l ->
                    "${GameTextFilter.judge(l, frame.height)}@${l.bounds.left},${l.bounds.top},${l.bounds.width}x${l.bounds.height}" +
                        " t=${l.typeHeight} e=${"%.0f".format(l.backdropError)}"
                },
            )
        }
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
            otherScript = null
            lastSelected = emptyList()
            motion = 0.0
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
        val lines = result.textBlocks.flatMap { it.lines }.flatMap { line -> separated(line) }.mapNotNull { (text, box) ->
            if (text.isBlank()) return@mapNotNull null
            val bounds = TextBounds(box.left, box.top + top, box.right, box.bottom + top, CoordinateSpace.SCREEN)
            GameLine(GameLayout.withoutStrayStroke(text), bounds, backdropError(frame, width, height, bounds), typeHeight(frame, bounds))
        }
        // Only a speaker's row is cut by colour: a name and its affiliation
        // sit over a line of dialogue. A label over a progress bar has its
        // halves on different colours of bar, and 「ダウンロード中」 came back
        // cut in two and translated as "down" and "loading".
        val around = lines + lastLines
        lines.flatMap { line ->
            if (around.any { it !== line && GameLayout.speaksAbove(line.bounds, it.bounds) }) splitByColour(frame, line) else listOf(line)
        }
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (failure: Throwable) {
        logger.warn(TAG, "recognition failed", failure)
        emptyList()
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
        val elements = line.elements.filter { it.boundingBox != null }
        if (elements.size < 2) return listOf(line.text to box)
        val groups = mutableListOf(mutableListOf(elements.first()))
        for (e in elements.drop(1)) {
            val gap = e.boundingBox!!.left - groups.last().last().boundingBox!!.right
            if (gap > box.height()) groups += mutableListOf(e) else groups.last() += e
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
        val panel = DoubleArray(box.width) { (luma(outsideTop[it]) + luma(outsideBottom[it])) / 2 }
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
        return if (best == 0) h else best
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
                    recognizer.process(InputImage.fromBitmap(crop, 0)).await().text.replace("\n", "").trim()
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
    private fun lookOf(frame: Bitmap, recognised: GameLine, others: List<GameLine>): Pair<GameLine, SourceStyle> {
        // The ink, not the recogniser's box, is what has to be covered: a
        // name read while it was still sliding in had a box missing its top,
        // and a line read without its opening 「……」 left the dots showing.
        // Never past halfway to the next piece on its row, though: a short
        // name's patch grew into the affiliation beside it and erased 「玄」.
        val b = recognised.bounds
        val row = others.filter { it.bounds != b && sameRow(it.bounds, b) }
        val leftLimit = row.filter { it.bounds.right <= b.left }.maxOfOrNull { (it.bounds.right + b.left) / 2 } ?: 0
        val rightLimit = row.filter { it.bounds.left >= b.right }.minOfOrNull { (it.bounds.left + b.right) / 2 } ?: frame.width
        val line = recognised.copy(bounds = inkExtent(frame, b, recognised.typeHeight, leftLimit, rightLimit))
        // Room for a line of type the original's height: a typeface's line is
        // taller than its glyphs' ink, and in the recogniser's tight box the
        // translation was shrunk to about 60% of the original to fit.
        val lineRoom = (line.typeHeight * TYPE_LINE_RATIO).toInt() - line.bounds.height
        val rowMargin = maxOf(MIN_ERASE_MARGIN_PX, line.typeHeight / ERASE_MARGIN_DIVISOR, lineRoom / 2 + 2)
        val backdrop = if (line.backdropError <= MAX_ERASE_ERROR) rowGradient(frame, line.bounds, SIDE_MARGIN_PX, rowMargin) else null
        // Where the panel is known, the ink is whatever stands out of it: a
        // short piece can count more dark outline than light letter, and
        // measured, one cyan 風紀委員会 came back navy.
        val lighterInk = backdrop?.let { luma(it.colorAt(it.rows / 2, 0.5f)) < MID_LUMA }
        val strokes = strokesOf(frame, line.bounds, lighterInk ?: true)
        return line to SourceStyle(
            foregroundColor = inkColour(frame, line.bounds, lighterInk),
            orientation = TextOrientation.HORIZONTAL,
            glyphSizePx = line.typeHeight,
            backdrop = backdrop,
            outlineColor = strokes.outline,
            strokeWeight = strokes.weight?.let { it / line.typeHeight.coerceAtLeast(1) },
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
        val weight = runs[runs.size / 2].toFloat()
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

    private fun inkExtent(frame: Bitmap, box: TextBounds, reach: Int, leftLimit: Int, rightLimit: Int): TextBounds {
        if (box.width <= 0 || box.height <= 0) return box
        fun inked(pixels: IntArray, lighter: Boolean): Boolean {
            val lumas = pixels.map { luma(it) }
            val bg = lumas.sorted()[lumas.size / 2]
            return lumas.count { (if (lighter) it - bg else bg - it) >= INK_CONTRAST } >= EXTENT_MIN_INK
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
        val gap = reach / 3
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
        return TextBounds(left, top, right, bottom, box.space)
    }

    /**
     * Per row of [box] grown by [margin] along it and [rowMargin] up and
     * down, the colour just outside it at each end: the mean of a few
     * columns, so one stray pixel of outline does not colour a whole row.
     * Null where the box runs off the frame.
     */
    private fun rowGradient(frame: Bitmap, box: TextBounds, margin: Int, rowMargin: Int): RowGradient? {
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
        val (l, r) = BackdropEdges.repaired(edge(left), edge(box.right + margin))
        return RowGradient(l, r, margin, rowMargin)
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

    private fun toElement(
        key: String,
        line: GameLine,
        style: SourceStyle,
        conversational: Boolean,
        window: TextBounds,
        packageName: String,
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
            sourceLanguage = JAPANESE.takeIf { JapaneseScript.isJapanese(line.text) },
            style = style,
        )
    }

    private companion object {
        const val TAG = "GameText"
        const val SAMPLE_STEP = 4

        /** Only a line several times wider than tall can hold two pieces. */
        const val MIN_SPLIT_ASPECT = 4
        const val MIN_PIECE_PX = 24
        const val MIN_RECOGNISABLE_PX = 32

        /** A speaker's name takes at most this much of its line: チナツ took 45%, レイジョ 61%. */
        const val MAX_NAME_FRACTION = 0.75
        const val SPLIT_CACHE = 64

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

        /** Middle of 0..255 brightness: a panel below it carries light ink. */
        const val MID_LUMA = 128.0

        /** How far above a line, in type heights, ruby is looked for, and the gap it may leave (1/n). */
        const val RUBY_REACH = 0.6
        const val RUBY_GAP_DIVISOR = 4

        /** How much further, in type heights, the ink is followed along a line than across it. */
        const val ALONG_REACH = 8

        /** Ink pixels that make a row or column beyond the box part of the line. */
        const val EXTENT_MIN_INK = 2

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
         * A scene is busy when most recent frames changed across half the
         * screen or more ([motion], smoothed over a few frames).
         */
        const val BUSY_SCENE = 0.6
        const val MOTION_SMOOTHING = 0.25

        /** Mean brightness change per grid cell, 0..255, that is a new scene. */
        const val SCENE_CHANGE_LUMA = 30.0

        /** A band this tall is a new screen, not a new line: read it whole. */
        const val WHOLE_FRAME_FRACTION = 0.5f
        val JAPANESE = LanguageTag("ja")
    }
}
