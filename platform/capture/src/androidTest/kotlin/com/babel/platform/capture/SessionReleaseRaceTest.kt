package com.babel.platform.capture

import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.babel.core.common.BabelLogger
import com.babel.core.common.DispatcherProvider
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Releasing a model while it is being used.
 *
 * This is the shape that killed the app on a real phone. The user reported the
 * accessibility service switching itself off and the process disappearing;
 * `adb logcat -b crash` had the reason:
 *
 * ```
 * F libc  : Fatal signal 11 (SIGSEGV), code 1 (SEGV_MAPERR), fault addr 0x0
 *           in tid 7502 (DefaultDispatch), pid 27358 (com.babel)
 * F DEBUG : Cause: null pointer dereference
 * F DEBUG : #00 pc ... libonnxruntime.so
 * ```
 *
 * `DefaultDispatch` is the dispatcher inference runs on. The session was taken
 * under a lock and then **used outside it**, while `release()` closed it under
 * the lock — so switching manga mode off during a page read freed memory that
 * native code was still reading. Intermittent, exactly as reported.
 *
 * None of the existing instrumentation could have caught it: every one of them
 * calls these objects in sequence. This one deliberately does not.
 *
 * A native crash cannot be asserted on — it takes the process, and with it the
 * test runner. **Reaching the end of this test is the assertion.** Before the
 * fix it does not reach the end; it dies.
 *
 * ```
 * adb shell am instrument -w -e class \
 *   com.babel.platform.capture.SessionReleaseRaceTest \
 *   com.babel.platform.capture.test/androidx.test.runner.AndroidJUnitRunner
 * ```
 */
@RunWith(AndroidJUnit4::class)
class SessionReleaseRaceTest {

    private val dispatchers = object : DispatcherProvider {
        override val main = Dispatchers.Main
        override val default = Dispatchers.Default
        override val io = Dispatchers.IO
    }

    private fun frame(): Bitmap = Bitmap.createBitmap(640, 640, Bitmap.Config.ARGB_8888)

    @Test
    fun releasingTheDetectorWhileItIsDetecting() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val detector = OnnxBubbleDetector(context, dispatchers, BabelLogger.NoOp)

        repeat(ROUNDS) {
            val work = (1..CONCURRENT).map { async { detector.detect(frame()) } }
            // Into the *middle* of one, which is what switching manga mode off
            // does while a page is being read. Launching the release alongside
            // is not enough — it tends to run after the detections finish, and
            // the window that matters is while native code holds the session.
            launch {
                delay(RELEASE_DELAY_MS)
                detector.release()
            }
            work.awaitAll()
        }

        // Still usable afterwards: releasing is a hand-back, not a breakage, so
        // the next scan has to be able to load it again.
        detector.detect(frame())
        assertTrue(detector.isAvailable, "the detector should reload after a release")
        detector.release()
    }

    @Test
    fun releasingTheRecogniserWhileItIsReading() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val manga = MangaOcrRecognizer(context, dispatchers, BabelLogger.NoOp)
        if (!manga.isAvailable) {
            // The weights are downloaded rather than bundled (ADR 011). Without
            // them there is no session to race against, and saying so beats a
            // test that quietly proves nothing.
            println("SESSIONRACE skipped: manga-ocr weights are not on this device")
            return@runBlocking
        }

        repeat(ROUNDS) {
            val work = (1..CONCURRENT).map { async { manga.recognize(frame()) } }
            launch {
                delay(RELEASE_DELAY_MS)
                manga.release()
            }
            work.awaitAll()
        }

        manga.recognize(frame())
        manga.release()
    }

    private companion object {
        /** Enough for the timing to land badly at least once. */
        const val ROUNDS = 20
        const val CONCURRENT = 3

        /**
         * Long enough that inference has started, short enough that it has not
         * finished. Detection is ~0.13s, so this lands inside it.
         */
        const val RELEASE_DELAY_MS = 30L
    }
}
