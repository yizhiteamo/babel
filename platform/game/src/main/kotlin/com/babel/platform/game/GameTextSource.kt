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
import kotlin.math.min
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
    private var latinVotes = 0.0

    /**
     * Kana seen lately: a game written in Japanese. Its kanji-only names —
     * 「風紀委員会」 — are then Japanese too, and are translated rather than
     * taken for Chinese already in the reader's language.
     */
    private var kanaVotes = 0.0
    private var otherVotes = 0.0
    private var lastSelected: List<GameLine> = emptyList()

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
            val fresh = if (bottom - top >= frame.height * WHOLE_FRAME_FRACTION) {
                how = "whole"
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
        )
        lastSelected = selected
        for (spot in GameLayout.speakerSpots(selected, minOf(frame.height, frame.width))) {
            val others = speakerSpots.filter { abs(it.left - spot.left) > spot.height / 2 || abs(it.top - spot.top) > spot.height / 2 }
            speakerSpots = (listOf(spot) + others).take(MAX_SPEAKER_SPOTS)
        }
        val now = System.currentTimeMillis()
        for (place in GameLayout.conversationPlaces(selected)) {
            conversation.removeAll { (old, _) -> covers(old, place) }
            conversation += place to now
        }
        conversation.removeAll { (_, at) -> now - at > CONVERSATION_KEPT_MS }
        while (conversation.size > MAX_CONVERSATION_PLACES) conversation.removeAt(0)
        val places = conversation.map { it.first }
        val kept = GameLayout.joinWrapped(GameLayout.joinRows(selected, GameLayout.dialogue(selected, places)))

        val dialogue = GameLayout.dialogue(kept, places)
        val speakers = GameLayout.speakers(kept, places)
        // Only what reads as dialogue: a line someone speaks, a narration, a
        // choice, an instruction or a pop-up's sentence — and the speaker's
        // name over it. A game's interface is left as it is. Measured on Blue
        // Archive's mission menu and Fate/Grand Order's battles: its labels
        // are short words, most of them kanji a Chinese reader reads anyway,
        // drawn on art the patch cannot paint back cleanly; translated, they
        // came out half-covered (「合同火力演習」 as 「大决战」 and 「演习」 over
        // what was left of it), came and went as the art moved, and stood
        // between the player and the buttons — for words learnt in an hour.
        val shown = kept.filter { it in dialogue }
        lock.withLock {
            val update = lines.next(shown, now = System.currentTimeMillis(), sceneChanged = sceneChanged) { gone ->
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
            }
            update.gone.forEach { erased.remove(it) }
            update.gone.forEach { publishedLooks.remove(it) }
            val gone = update.gone.mapNotNull { published.remove(it) }
            if (gone.isNotEmpty()) events.emit(TextSourceEvent.Removed(gone))
            if (update.gone.isNotEmpty()) {
                // Where and why, never what: the key's text is screen text.
                logger.debug(TAG, "withdrawn " + update.gone.joinToString { "${it.substringAfterLast('@')} ${update.why[it]}" })
            }

            fun elementOf(key: String, line: GameLine, conversational: Boolean, speaker: Boolean): TextElement {
                // Every line read, not only those kept: a name left untranslated
                // still has ink an affiliation's patch must not grow into.
                val (inked, look) = lookOf(frame, line, read, speaker)
                val element = toElement(key, inked, look, conversational, windowBounds, packageName, speaker = speaker)
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
                    read.any { abs(it.bounds.top - e.line.bounds.top) <= STILL_PX && abs(it.bounds.left - e.line.bounds.left) <= STILL_PX } &&
                    repainted(e.panel, panelAround(frame, e.line.bounds))
            }.map { (key, e) -> elementOf(key, e.line, e.conversational, e.speaker) }
            if (repainted.isNotEmpty()) {
                logger.debug(TAG, "repainted " + repainted.joinToString { "${it.id.value} weight=${it.style.strokeWeight?.let { w -> "%.2f".format(w) }}" })
            }
            // Every size settling now is seen before any is given out: lines
            // settle together, and the first set its size before the tallest
            // of its kind had been measured.
            update.settled.forEach { steadySize(it.line in speakers, it.line.typeHeight) }
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
        return then.indices.sumOf { abs(then[it] - now[it]) }.toDouble() / then.size > REPLACED_LOOK_LUMA
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
            lines = StableLines()
            lastGrid = null
            lastLines = emptyList()
            lastLooks = emptyMap()
            otherScript = null
            latinVotes = 0.0
            kanaVotes = 0.0
            otherVotes = 0.0
            lastSelected = emptyList()
            conversation.clear()
            labelWeight = 0f
            lineWeight = 0f
            labelWeightShown = 0f
            typeSizes.clear()
            lineWeightShown = 0f
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
            val read = TextBounds(box.left, box.top + top, box.right, box.bottom + top, CoordinateSpace.SCREEN)
            val (kept, bounds) = withoutTail(frame, GameLayout.withoutStrayStroke(text), onItsPanel(frame, read))
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
        // The panel just above and below the line, not inside it: a short
        // line of heavy type is more type than panel.
        val outside = IntArray(box.width - tail)
        val lumas = mutableListOf<Double>()
        for (y in listOf(box.top - 2, box.bottom + 1)) {
            frame.getPixels(outside, 0, outside.size, box.left, y, outside.size, 1)
            for (x in outside.indices step 2) lumas += luma(outside[x])
        }
        val panel = lumas.sorted()[lumas.size / 2]
        val sign = if (panel < MID_LUMA) 1.0 else -1.0
        val row = IntArray(tail)
        var ink = 0
        for (y in box.top until box.bottom) {
            frame.getPixels(row, 0, tail, box.right - tail, y, tail, 1)
            val inked = BooleanArray(tail) { (luma(row[it]) - panel) * sign >= INK_CONTRAST }
            // Not what runs in from the box's end: the page past the bubble.
            var end = tail
            while (end > 0 && inked[end - 1]) end--
            for (x in 0 until end) if (inked[x]) ink++
        }
        val share = ink.toDouble() / (tail * box.height)
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
    /**
     * One weight for a kind of line, not one per line: measured per line, the
     * strokes of one game's dialogue came out 0.13 on one line and 0.16 on the
     * next, and consecutive lines of one box were set in two weights. Names
     * and dialogue each keep a running mean.
     */
    private var labelWeight = 0f
    private var lineWeight = 0f

    /**
     * One size for one size of type, not one per line: the height of a line's
     * ink depends on its letters — a chat's 「はい」 measured 37 px, its
     * bubbles of kanji 41, in one font of one size — and each translation was
     * set at its own line's measure. Sizes within [SAME_TYPE_RATIO] of a size
     * already seen are that size, the tallest measured, which is the type's.
     * Names and lines apart: a game sets its names smaller or larger.
     */
    private class TypeSize(val speaker: Boolean, var smallest: Int, var tallest: Int)
    private val typeSizes = mutableListOf<TypeSize>()

    private fun steadySize(speaker: Boolean, measured: Int): Int {
        // Within the ratio of the smallest and the tallest alike, so a size
        // cannot creep up line by line into the next size of type.
        val size = typeSizes.firstOrNull { t ->
            t.speaker == speaker && min(t.smallest, measured).toDouble() / max(t.tallest, measured) >= SAME_TYPE_RATIO
        }
        if (size == null) {
            typeSizes += TypeSize(speaker, measured, measured)
            return measured
        }
        size.smallest = min(size.smallest, measured)
        size.tallest = max(size.tallest, measured)
        return size.tallest
    }

    /**
     * What was last given out for each kind: the running mean of a chat's
     * bubbles hovered at 0.14–0.15, either side of a step in weight, and
     * consecutive bubbles were set in two. It changes only once the mean has
     * moved clearly away.
     */
    private var labelWeightShown = 0f
    private var lineWeightShown = 0f

    private fun steadyWeight(label: Boolean, measured: Float): Float {
        val before = if (label) labelWeight else lineWeight
        val now = if (before == 0f) measured else before * (1 - WEIGHT_SMOOTHING) + measured * WEIGHT_SMOOTHING
        if (label) labelWeight = now else lineWeight = now
        val shown = if (label) labelWeightShown else lineWeightShown
        if (shown != 0f && abs(now - shown) < WEIGHT_HYSTERESIS) return shown
        if (label) labelWeightShown = now else lineWeightShown = now
        return now
    }

    private fun lookOf(frame: Bitmap, recognised: GameLine, others: List<GameLine>, speaker: Boolean): Pair<GameLine, SourceStyle> {
        // The ink, not the recogniser's box, is what has to be covered: a
        // name read while it was still sliding in had a box missing its top,
        // and a line read without its opening 「……」 left the dots showing.
        // Never past halfway to the next piece on its row, though: a short
        // name's patch grew into the affiliation beside it and erased 「玄」.
        val b = recognised.bounds
        val row = others.filter { it.bounds != b && sameRow(it.bounds, b) }
        val leftLimit = row.filter { it.bounds.right <= b.left }.maxOfOrNull { (it.bounds.right + b.left) / 2 } ?: 0
        val rightLimit = row.filter { it.bounds.left >= b.right }.minOfOrNull { (it.bounds.left + b.right) / 2 } ?: frame.width
        val extent = inkExtent(frame, b, recognised.typeHeight, leftLimit, rightLimit)
        // Centred on the screen — a choice, a banner — and set again centred,
        // over a box made even about the centre: the ink to one side reached
        // further than the other once a closing 」 stood apart, and was left
        // standing beside a translation set from the left.
        val mid = frame.width / 2
        // Not a line of the dialogue box, which runs from the box's margin
        // and can be centred by chance: a two-line utterance was set centred.
        val centred = row.isEmpty() && extent.width < frame.width * MAX_CENTRED_WIDTH &&
            extent.left > frame.width * MIN_CENTRED_LEFT &&
            abs((extent.left + extent.right) / 2 - mid) <= frame.width * CENTRED_TOLERANCE
        // Followed across wider gaps too: a choice's quotation marks stand a
        // character and a half off its words, and were left beside them.
        val wide = if (centred) inkExtent(frame, extent, recognised.typeHeight, leftLimit, rightLimit, gapLines = CENTRED_GAP_LINES) else extent
        val half = max(mid - wide.left, wide.right - mid)
        // Past the end of a line set from the left, the plain panel it stands
        // on is room for a translation a little wider than the original: in a
        // chat's bubble, 「打扫?」 for 「掃除?」 was a few pixels too wide for the
        // original's ink and was shrunk, smaller than every bubble around it.
        val room = if (centred) 0 else plainAfter(frame, extent, minOf(rightLimit - SIDE_MARGIN_PX - EDGE_SAMPLE_COLUMNS, frame.width), (recognised.typeHeight * ROOM_OF_TYPE).toInt())
        val line = recognised.copy(
            bounds = if (centred) {
                TextBounds(mid - half, wide.top, mid + half, wide.bottom, wide.space)
            } else {
                TextBounds(extent.left, extent.top, extent.right + room, extent.bottom, extent.space)
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
        return line to SourceStyle(
            foregroundColor = inkColour(frame, line.bounds, lighterInk),
            orientation = TextOrientation.HORIZONTAL,
            glyphSizePx = steadySize(speaker, line.typeHeight),
            backdrop = backdrop,
            outlineColor = strokes.outline,
            // Unmeasured, the weight of its kind: left out, a line came out
            // regular among bold ones of the same type.
            strokeWeight = strokes.weight?.let { steadyWeight(speaker, it / line.typeHeight.coerceAtLeast(1)) }
                ?: (if (speaker) labelWeightShown else lineWeightShown).takeIf { it > 0f },
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
    private fun plainAfter(frame: Bitmap, box: TextBounds, limit: Int, most: Int): Int {
        val h = box.bottom - box.top
        if (h <= 0 || box.top < 0 || box.bottom > frame.height) return 0
        val column = IntArray(h)
        fun plain(x: Int): Double? {
            frame.getPixels(column, 0, 1, x, box.top, 1, h)
            val lumas = column.map { luma(it) }
            val mid = lumas.sorted()[h / 2]
            return if (lumas.any { abs(it - mid) > BARE_LUMA }) null else mid
        }
        // From the first plain column: the box can end on the last glyph's
        // softened edge, and 「先生!」 got no room at all past its 「!」.
        var start = 0
        while (start < LEAD_IN_PX && box.right + start < limit && plain(box.right + start) == null) start++
        val reference = if (box.right + start < limit) plain(box.right + start) ?: return 0 else return 0
        var n = start
        while (n < most && box.right + n + SIDE_MARGIN_PX + EDGE_SAMPLE_COLUMNS < limit) {
            val mid = plain(box.right + n) ?: break
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
        abs(was.first - now.first) > PANEL_CHANGED_LUMA || abs(was.second - now.second) > PANEL_CHANGED_LUMA

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

    private fun toElement(
        key: String,
        line: GameLine,
        style: SourceStyle,
        conversational: Boolean,
        window: TextBounds,
        packageName: String,
        speaker: Boolean = false,
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

        /** A speaker's name takes at most this much of its line: チナツ took 45%, レイジョ 61%, 「玄龍門の構成員」 beside 「玄龍門」 over 75%. */
        const val MAX_NAME_FRACTION = 0.85
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

        /** A line's look: this grid, and how far (0..255) any cell may change and be the same. */
        const val LOOK_COLUMNS = 24
        const val LOOK_ROWS = 4
        const val REPLACED_LOOK_LUMA = 16.0
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

        /** How much each line moves the running stroke weight. */
        const val WEIGHT_SMOOTHING = 0.1f

        /** Type heights this close are one size of type, measured on different letters. */
        const val SAME_TYPE_RATIO = 0.86

        /** How far the running stroke weight moves before the weight given out follows it. */
        const val WEIGHT_HYSTERESIS = 0.03f

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

        /** What is kept of an empty end a box is cut back from. */
        const val SHRINK_SLACK_PX = 2
        const val BARE_LUMA = 10.0
        const val BARE_STRAY_PX = 2

        /** How far past a box's end its last glyph's edge may still reach. */
        const val LEAD_IN_PX = 6

        /** How much plain panel past a line's end may be taken as room, against its type's height. */
        const val ROOM_OF_TYPE = 1.0

        /** How much of the line's end, against its height, a last mark is looked for in: about one narrow glyph. */
        const val TAIL_WIDTH_OF_HEIGHT = 0.4
        const val TAIL_MARKS = "?？>〉"

        /** Less type than this in the last mark's width, and the mark was not there. */
        const val TAIL_MAX_INK = 0.05

        /** How far the panel under a painted-out line may change before it is painted again. */
        const val PANEL_CHANGED_LUMA = 10.0

        /** How far a line's box may wobble and be the same line in the same place. */
        const val STILL_PX = 4

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

        /** A band this tall is a new screen, not a new line: read it whole. */
        const val WHOLE_FRAME_FRACTION = 0.5f
        val JAPANESE = LanguageTag("ja")
    }
}
