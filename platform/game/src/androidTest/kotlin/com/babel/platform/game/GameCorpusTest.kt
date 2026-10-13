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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The rules for what a game's screen is translated by, held against every
 * scene in `game-corpus` at once — `docs/systems/game-text.md`.
 *
 * Each scene is a clean screenshot (game mode off) and a `.expect` file of
 * lines `+ words` (some published line must hold them) and `- words` (no
 * published line may). A rule changed for one scene is run against all of
 * them, so a fix for a chat does not quietly start translating a menu bar.
 * No corpus on the device, nothing is checked.
 *
 * Text is printed: the strings are the measurement (`docs/systems/privacy.md`).
 */
@RunWith(AndroidJUnit4::class)
class GameCorpusTest {

    @Test
    fun corpus() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val dir = File(context.getExternalFilesDir(null), "game-corpus")
        val scenes = dir.listFiles { f -> f.name.endsWith(".png") }?.sortedBy { it.name }.orEmpty()
        val failures = mutableListOf<String>()
        var checks = 0
        for (file in scenes) {
            val expect = File(dir, file.nameWithoutExtension + ".expect").takeIf { it.exists() }?.readLines().orEmpty()
                .filter { it.length > 2 && (it[0] == '+' || it[0] == '-') }
            val still = BitmapFactory.decodeFile(file.absolutePath) ?: continue
            val published = publishedText(still)
            still.recycle()
            for (rule in expect) {
                checks++
                val words = rule.substring(1).trim()
                val found = published.any { words in it }
                val ok = if (rule[0] == '+') found else !found
                if (!ok) failures += "${file.nameWithoutExtension}: ${if (rule[0] == '+') "missing" else "translated"} 「$words」"
            }
            println("GAMECORPUS ${file.nameWithoutExtension}: ${published.size} published")
            published.forEach { println("GAMECORPUS   $it") }
            lastReading.lines().forEach { println("GAMECORPUS read $it") }
        }
        failures.forEach { println("GAMECORPUS FAIL $it") }
        println("GAMECORPUS done: ${checks - failures.size}/$checks")
        assertTrue("${failures.size} of $checks expectations failed", failures.isEmpty())
    }

    /** What [still], shown as a game window for a few frames, comes to publish. */
    private suspend fun publishedText(still: Bitmap): List<String> = kotlinx.coroutines.coroutineScope {
        var served = 0
        val frames = object : ScreenFrameSource {
            override val isSupported = true
            override val isAvailable = true
            override suspend fun latestFrame(): Bitmap? = null
            override val supportsWindowFrames = true
            override suspend fun windowFrame(windowId: Int): Bitmap? {
                if (served > 0) delay(FRAME_INTERVAL_MS)
                served++
                return still.copy(Bitmap.Config.ARGB_8888, false)
            }
        }
        val source = GameTextSource(frames, BabelLogger.NoOp)
        var last = ""
        source.inspect = { read, kept, dialogue, speakers, why ->
            last = read.joinToString(NL) { l ->
                val k = kept.firstOrNull { it.bounds == l.bounds || (it.text.contains(l.text) && it.bounds.left <= l.bounds.left && it.bounds.right >= l.bounds.right && it.bounds.top <= l.bounds.top && it.bounds.bottom >= l.bounds.bottom) }
                val mark = when {
                    k == null -> "  "
                    k in speakers -> "N "
                    k in dialogue -> "D "
                    else -> "k "
                }
                "$mark${k?.let { why[it] } ?: ""} ${l.text}@${l.bounds.left},${l.bounds.top},${l.bounds.width}x${l.bounds.height} t=${l.typeHeight} e=${"%.0f".format(l.backdropError)}"
            }
        }
        val events = mutableListOf<TextSourceEvent>()
        val collector = launch { source.events().collect { events += it } }
        val window = TextBounds(0, 0, still.width, still.height, CoordinateSpace.SCREEN)
        source.read(1, window, "corpus") { served < FRAMES }
        collector.cancel()
        source.release()
        val shown = LinkedHashMap<String, String>()
        for (event in events) when (event) {
            is TextSourceEvent.Upserted -> event.elements.forEach { shown[it.id.value] = it.text }
            is TextSourceEvent.Removed -> event.ids.forEach { shown.remove(it.value) }
            else -> Unit
        }
        lastReading = last
        shown.values.toList()
    }

    private var lastReading = ""

    private companion object {
        const val FRAMES = 4
        const val NL = "\n"
        const val FRAME_INTERVAL_MS = 1_500L
    }
}
