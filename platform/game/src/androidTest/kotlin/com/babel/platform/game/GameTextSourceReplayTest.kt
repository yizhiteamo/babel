package com.babel.platform.game

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.babel.core.common.BabelLogger
import com.babel.core.model.CoordinateSpace
import com.babel.core.model.TextBounds
import com.babel.domain.acquisition.TextSourceEvent
import com.babel.platform.screen.ScreenFrameSource
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Each screenshot in `game-sample` fed to [GameTextSource] as a still game
 * window, three frames of it, printing what it publishes — the whole path of
 * reading, splitting, selecting, joining and settling, on real pictures.
 *
 * Text is printed: the strings are the measurement (`docs/systems/privacy.md`).
 */
@RunWith(AndroidJUnit4::class)
class GameTextSourceReplayTest {

    @Test
    fun replay() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val shots = File(context.getExternalFilesDir(null), "game-sample").listFiles()?.sortedBy { it.name }.orEmpty()
        for (file in shots) {
            val still = BitmapFactory.decodeFile(file.absolutePath) ?: continue
            var served = 0
            val reads = AtomicInteger()
            val done = CompletableDeferred<Unit>()
            val frames = object : ScreenFrameSource {
                override val isSupported = true
                override val isAvailable = true
                override suspend fun latestFrame(): Bitmap? = null
                override val supportsWindowFrames = true
                override suspend fun windowFrame(windowId: Int): Bitmap? {
                    // Keep supplying frames at [FRAME_INTERVAL_MS] until
                    // [FRAMES] readings have completed, regardless of how long
                    // each reading takes on the device.
                    if (served > 0) withTimeoutOrNull(FRAME_INTERVAL_MS) { done.await() }
                    if (reads.get() >= FRAMES) return null
                    served++
                    return still.copy(Bitmap.Config.ARGB_8888, false)
                }
            }
            val source = GameTextSource(frames, BabelLogger.NoOp)
            source.inspect = { _, _, _, _, _ ->
                if (reads.incrementAndGet() >= FRAMES) done.complete(Unit)
            }
            val events = mutableListOf<TextSourceEvent>()
            val collector = launch(start = CoroutineStart.UNDISPATCHED) { source.events().collect { events += it } }
            val window = TextBounds(0, 0, still.width, still.height, CoordinateSpace.SCREEN)
            source.read(1, window, "replay") { reads.get() < FRAMES }
            yield()
            collector.cancel()
            println("GAMEREPLAY === ${file.name}")
            events.filterIsInstance<TextSourceEvent.Upserted>().flatMap { it.elements }.forEach {
                val b = it.bounds
                val style = it.style
                val ink = style.foregroundColor?.let { c -> "%06x".format(c and 0xFFFFFF) }
                val look = "type=${style.glyphSizePx} ink=$ink " +
                    (style.backdrop?.let { g -> "erased(${g.rows} rows)" } ?: "boxed")
                println("GAMEREPLAY   ${b.left},${b.top},${b.width}x${b.height}\t$look\t${it.text}")
            }
            source.release()
            still.recycle()
        }
        println("GAMEREPLAY done")
    }

    private companion object {
        const val FRAMES = 4
        const val FRAME_INTERVAL_MS = 2_000L
    }
}
