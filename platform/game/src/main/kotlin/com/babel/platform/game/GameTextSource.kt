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

    override suspend fun scan(windowId: Int, windowBounds: TextBounds, packageName: String) {
        val frame = frames.windowFrame(windowId) ?: return
        try {
            val started = System.currentTimeMillis()
            val read = recognise(frame)
            val readMs = System.currentTimeMillis() - started

            val kept = read.filter { GameTextFilter.judge(it, frame.height) == GameTextFilter.Verdict.TRANSLATE }

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
            logger.debug(TAG, "read ${read.size} lines, kept ${kept.size}, in ${readMs}ms")
        } finally {
            frame.recycle()
        }
    }

    override suspend fun clear() {
        lock.withLock {
            lines.reset()
            published.clear()
            lines = StableLines()
            session += 1
        }
        events.emit(TextSourceEvent.Cleared(TextSourceType.GAME))
    }

    override suspend fun release() {
        runCatching { recognizer.close() }
    }

    private suspend fun recognise(frame: Bitmap): List<GameLine> = try {
        val result = recognizer.process(InputImage.fromBitmap(frame, 0)).await()
        val width = frame.width
        val height = frame.height
        val pixels = IntArray(width * height).also { frame.getPixels(it, 0, width, 0, 0, width, height) }
        result.textBlocks.flatMap { it.lines }.mapNotNull { line ->
            val box = line.boundingBox ?: return@mapNotNull null
            if (line.text.isBlank()) return@mapNotNull null
            val bounds = TextBounds(box.left, box.top, box.right, box.bottom, CoordinateSpace.SCREEN)
            GameLine(line.text, bounds, backdropError(pixels, width, height, bounds))
        }
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
    private fun backdropError(pixels: IntArray, width: Int, height: Int, box: TextBounds): Double {
        val pad = max(6, box.height / 4)
        val left = box.left - 1
        val right = box.right
        if (left < 0 || right >= width) return Double.MAX_VALUE
        var sum = 0.0
        var count = 0
        val rows = (box.top - pad until box.top) + (box.bottom until box.bottom + pad)
        for (y in rows) {
            if (y < 0 || y >= height) continue
            val row = y * width
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
            source = SourceIdentity(packageName = packageName),
            revision = Revision(session),
            sourceLanguage = JAPANESE.takeIf { JapaneseScript.isJapanese(line.text) },
        )
    }

    private companion object {
        const val TAG = "GameText"
        const val SAMPLE_STEP = 4
        val JAPANESE = LanguageTag("ja")
    }
}
