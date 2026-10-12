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
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.takeWhile
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
            val frames = object : ScreenFrameSource {
                override val isSupported = true
                override val isAvailable = true
                override suspend fun latestFrame(): Bitmap? = null
                override val supportsWindowFrames = true
                override suspend fun windowFrame(windowId: Int): Bitmap? {
                    // Pictures are taken while the last is read; paced slower
                    // than a reading takes, so every one of them is read, as
                    // the measurement assumes.
                    if (served > 0) kotlinx.coroutines.delay(FRAME_INTERVAL_MS)
                    served++
                    return still.copy(Bitmap.Config.ARGB_8888, false)
                }
            }
            val source = GameTextSource(frames, BabelLogger.NoOp)
            val events = mutableListOf<TextSourceEvent>()
            val collector = launch { source.events().collect { events += it } }
            val window = TextBounds(0, 0, still.width, still.height, CoordinateSpace.SCREEN)
            source.read(1, window, "replay") { served < FRAMES }
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
