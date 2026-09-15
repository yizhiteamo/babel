package com.babel.data.translation.mlkit

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.babel.core.model.LanguagePair
import com.babel.core.model.LanguageTag
import com.babel.core.model.RequestId
import com.babel.core.model.Revision
import com.babel.core.model.TextElementId
import com.babel.core.model.TranslationRequest
import com.babel.core.model.TranslationStatus
import com.babel.domain.translation.FragmentingTranslator
import com.babel.domain.translation.Translator
import com.babel.domain.vision.OcrPunctuation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Stage 3, the shipped engine: how much of the translation gap is the **unit**
 * being translated, and how much is the engine.
 *
 * Prompted by a comparison a user ran against GPT. GPT was handed
 * `はい いくらでも使ってください` as one balloon and answered with one sentence.
 * This project had been measuring on `はい` and `いくらでも使ってください`
 * separately — and nothing translates `はい` on its own. The earlier conclusion
 * that engines "collapse on short bubbles" confused two different things.
 *
 * ## What is varied
 *
 * **Join mode** — how a balloon's lines reach the engine:
 * - `fragments` — each line on its own, which is what a mis-grouped page gives
 *   and what every earlier measurement here used
 * - `joined` — run together with no separator, exactly what `TextRegion.text`
 *   builds today when grouping is right
 *
 * **Translator** — with and without [FragmentingTranslator], which splits at
 * ellipses. It measured as a clear win on fragment input, but it makes the unit
 * *smaller*, which is the opposite of what this experiment suspects is needed.
 * It ships wrapped around the provider today, so the question is live.
 *
 * Prints rather than asserts: translation quality is a judgement, and a single
 * reference scored automatically would be a worse guide than reading the output.
 *
 * The model download is done once, before anything is timed, and reported
 * separately — folding it into the first bubble is how this harness once timed
 * out at 60s and reported nothing.
 */
@RunWith(AndroidJUnit4::class)
class TranslationExperimentTest {

    private val provider = MlKitTranslator()
    private val fragmenting: Translator = FragmentingTranslator(provider)

    @After
    fun tearDown() = provider.close()

    /**
     * The material as balloons, each given as the lines manga-ocr reads from it.
     *
     * jap-mag-01 is five balloons read as seven regions; jap-mag-04 is eight
     * read as eight. So the splitting shows on the first page only, which makes
     * the comparison a sharp one.
     *
     * References for jap-mag-01 are GPT's, quoted from the report that prompted
     * this; jap-mag-04's are the ones this project already used.
     */
    private val balloons: List<Pair<List<String>, String>> = listOf(
        listOf("先生も汗拭きシート使いますか?") to "老师也用擦汗湿巾吗？",
        listOf("いいの?") to "可以吗？",
        listOf("はい", "いくらでも使ってください") to "嗯，请尽管用，想用多少都可以。",
        listOf("…あ", "そっちは私の使用済み…") to "……啊，那个是我已经用过的……",
        listOf("…先生?", "先生?") to "……老师？老师？",
        listOf("スーパーアルバイターの資格、次が最終試験…この本も最終ですッ") to
            "超级兼职者的资格，下一场就是最终考试…这本书也是最后一本了！",
        listOf("どんなことが書かれて…") to "上面写了些什么…",
        listOf("…仕事中、突然視界が高くなったり…増えたり…手足…色…声が変化して…") to
            "工作时视野会突然变高…会增多…手脚、颜色、声音都会变化…",
        listOf("周囲が泣いたり、騒いだり逃げ出した時…店主の…言葉や誘導は無視して…") to
            "周围的人哭喊、骚动、逃跑时……请无视店主的话和指引……",
        listOf("目を…閉じて…") to "闭上眼睛…",
        listOf("…絶対に…動かないこと…") to "绝对…不要动…",
        listOf("…あんまりわかんないケドッ") to "……虽然不太懂啦",
        listOf("がんばりまーす!!") to "我会加油的！！",
    )

    @Test
    fun compareBalloonAgainstFragmentInput() = runBlocking {
        val downloadStarted = System.currentTimeMillis()
        // The generous timeout has to be the *inner* one: a `withTimeout` around
        // a call that has its own shorter one is decided by the shorter.
        translate(provider, "こんにちは", DOWNLOAD_TIMEOUT_MS)
        println("CLEAN_MT models ready in ${System.currentTimeMillis() - downloadStarted}ms")
        println("CLEAN_MT engine=mlkit ja>zh, ${balloons.size} balloons")

        for ((mode, split) in JOIN_MODES) {
            var total = 0L
            var calls = 0
            println("CLEAN_MT ======== join mode: $mode ========")

            for ((lines, reference) in balloons) {
                val sources = split(lines)
                println("CLEAN_MT ---")
                println("CLEAN_MT   ja         ${sources.joinToString(" | ")}")
                println("CLEAN_MT   ref        $reference")

                for ((variant, translator) in translators) {
                    val started = System.currentTimeMillis()
                    val outputs = sources.map { translate(translator, it) }
                    val elapsed = System.currentTimeMillis() - started
                    if (variant == "plain     ") {
                        total += elapsed
                        calls += sources.size
                    }
                    println("CLEAN_MT   $variant ${elapsed}ms  \"${outputs.joinToString(" | ")}\"")
                }
            }

            println("CLEAN_MT === $mode: ${balloons.size} balloons, $calls calls, ${total}ms ===")
        }
    }

    /** Named so the summary and the per-balloon lines cannot disagree. */
    private val translators: Map<String, Translator> = linkedMapOf(
        "plain     " to provider,
        "fragmented" to fragmenting,
    )

    private companion object {
        const val TIMEOUT_MS = 30_000L
        const val DOWNLOAD_TIMEOUT_MS = 600_000L

        /** How a balloon's lines reach the engine. See the class comment. */
        val JOIN_MODES: List<Pair<String, (List<String>) -> List<String>>> = listOf(
            "fragments" to { lines -> lines },
            "joined" to { lines -> listOf(lines.joinToString("")) },
        )
    }

    private suspend fun translate(
        translator: Translator,
        text: String,
        timeoutMs: Long = TIMEOUT_MS,
    ): String {
        val result = withTimeout(timeoutMs) {
            translator.translate(
                TranslationRequest(
                    requestId = RequestId("experiment"),
                    elementId = TextElementId("e"),
                    revision = Revision(0),
                    sourceText = text,
                    // Declared rather than detected: manga mode knows its
                    // recogniser reads Japanese, and detection on OCR output
                    // was measurably unreliable.
                    languages = LanguagePair(
                        source = LanguageTag("ja"),
                        target = LanguageTag("zh"),
                    ),
                ),
            )
        }
        return when (result.status) {
            TranslationStatus.Translated -> result.translatedText
            else -> "<${result.status}>"
        }
    }
}
