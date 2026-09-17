package com.babel.platform.capture

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.babel.core.common.BabelLogger
import com.babel.core.common.DispatcherProvider
import com.babel.domain.vision.RecognizerModelState
import java.io.File
import java.security.MessageDigest
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Exercises the real download against the real host, for 24 kilobytes.
 *
 * `vocab.txt` is the smallest of the three files and takes exactly the same
 * path as the other two: a request to the pinned URL, a stream onto a `.part`
 * file, a SHA-256 over what arrived, and a rename only if it matches. The
 * encoder and decoder differ from it in size and in nothing else, so proving
 * this for 24KB proves the mechanism without spending 117MB of somebody's
 * connection on a test.
 *
 * The two large files are stood in for by empty placeholders, because
 * [ModelDownloader.install] skips whatever already exists — by name, which is
 * its documented behaviour and the same thing that makes a second run cheap.
 * Without them this would fetch 117MB every time it ran.
 *
 * Note the directory: a library module's instrumentation is self-instrumented,
 * so this works inside `com.babel.platform.capture.test`'s own external files
 * directory, never the app's. Nothing here can disturb a real installation.
 *
 * ```
 * ./gradlew :platform:capture:connectedDebugAndroidTest
 *   -Pandroid.testInstrumentationRunnerArguments.class=
 *     com.babel.platform.capture.ModelDownloaderTest
 * ```
 */
@RunWith(AndroidJUnit4::class)
class ModelDownloaderTest {

    private val dispatchers = object : DispatcherProvider {
        override val main = Dispatchers.Main
        override val default = Dispatchers.Default
        override val io = Dispatchers.IO
    }

    private val expectedVocabHash =
        "5cb5c5586d98a2f331d9f8828e4586479b0611bfba5d8c3b6dadffc84d6a36a3"

    @Test
    fun itFetchesVerifiesAndInstallsAMissingFile() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val models = File(context.getExternalFilesDir(null), "models")
        models.mkdirs()
        val vocab = File(models, "vocab.txt")

        // Empty, and that is the point: install() asks whether a file is there,
        // not whether it is right — the hash decides that, and it is checked on
        // what arrives rather than on what is already installed.
        val placeholders = listOf("encoder_model_int8.onnx", "decoder_model_int8.onnx")
            .map { File(models, it) }
        placeholders.forEach { it.createNewFile() }
        vocab.delete()
        File(models, "vocab.txt.part").delete()

        try {
            val downloader = ModelDownloader(context, dispatchers, BabelLogger.NoOp)
            assertEquals(
                RecognizerModelState.Absent,
                downloader.state.value,
                "one missing file should read as absent",
            )

            downloader.install()

            assertEquals(RecognizerModelState.Installed, downloader.state.value)
            assertTrue(vocab.exists(), "the file was not installed")
            assertEquals(
                expectedVocabHash,
                vocab.sha256(),
                "what was installed is not what was pinned",
            )
            // The temporary must not survive a success, or the next run would
            // resume onto bytes that are already accounted for.
            assertTrue(
                !File(models, "vocab.txt.part").exists(),
                "a .part file was left behind",
            )
        } finally {
            placeholders.forEach { it.delete() }
            vocab.delete()
        }
    }

    private fun File.sha256(): String {
        val digest = MessageDigest.getInstance("SHA-256")
        inputStream().use { stream ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = stream.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
