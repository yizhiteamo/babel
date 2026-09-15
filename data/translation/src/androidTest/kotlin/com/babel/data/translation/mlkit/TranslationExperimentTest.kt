package com.babel.data.translation.mlkit

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.babel.core.model.LanguagePair
import com.babel.core.model.LanguageTag
import com.babel.core.model.RequestId
import com.babel.core.model.Revision
import com.babel.core.model.TextElementId
import com.babel.core.model.TranslationRequest
import com.babel.core.model.TranslationStatus
import com.babel.domain.vision.OcrPunctuation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Stage 3, the shipped engine: what ML Kit makes of **clean** Japanese, and
 * whether repairing OCR punctuation first changes the answer.
 *
 * Every complaint about translation quality so far was measured on text the
 * recogniser had mangled, and it was never clear how much was the translator's
 * fault rather than its input's. manga-ocr reads these bubbles exactly, so this
 * feeds the real sentences in and prints what comes out. It is the control the
 * candidate engines are compared against.
 *
 * ## The three variants
 *
 * - **raw** — the text as manga-ocr produced it, ellipses and all
 * - **normalised** — [OcrPunctuation.normalize], so `......` is one `…`
 * - **stripped** — normalised, then leading and trailing punctuation split off
 *   before translating and reattached afterwards, so the provider sees a
 *   sentence and the reader still sees the ellipsis
 * - **fragmented** — cut at *every* ellipsis, each fragment translated on
 *   its own and the ellipses put back between them
 *
 * The point is to find out which is worth building, and whether replacing the
 * translation engine is worth considering at all. If clean input plus punctuation
 * repair is enough, two more models need not be downloaded, shipped and
 * maintained.
 *
 * Prints rather than asserts: translation quality is a judgement, and a single
 * reference translation scored automatically would be a worse guide than reading
 * the output. The one assertion is the empty-output count, which is not a
 * judgement — an empty translation is a bubble that renders as nothing.
 *
 * ## Reading the timings
 *
 * The model download is done once, before anything is timed, and reported
 * separately. Folding it into the first bubble is how this test previously came
 * to time out at 60s and report nothing at all.
 */
@RunWith(AndroidJUnit4::class)
class TranslationExperimentTest {

    private val translator = MlKitTranslator()

    @After
    fun tearDown() = translator.close()

    /**
     * Exactly what manga-ocr read from the two transcribed pages, punctuation
     * and all, paired with a reference rendering for comparison.
     */
    private val bubbles = listOf(
        "先生も汗拭きシート使いますか?" to "老师也要用擦汗巾吗？",
        "いいの?" to "可以吗？",
        "はい" to "好的",
        "いくらでも使ってください" to "请随便用",
        "そっちは私の使用済み:" to "那个是我用过的…",
        "......あ" to "……啊",
        "......先生?" to "……老师？",
        "スーパーアルバイターの資格、次が最終試験...この本も最終ですッ" to
            "超级兼职者的资格，下一场就是最终考试…这本书也是最后一本了！",
        "どんなことが書かれて..." to "上面写了些什么…",
        "...仕事中、突然視界が高くなったり...増えたり...手足..色...声が変化して..." to
            "工作时视野会突然变高…会增多…手脚、颜色、声音都会变化…",
        "周囲が泣いたり、騒いだり逃げ出した時......店主の...言葉や誘導は無視して......." to
            "周围的人哭喊、骚动、逃跑时……请无视店主的话和指引……",
        "目を...閉じて..." to "闭上眼睛…",
        "...絶対に...動かないこと..." to "绝对…不要动…",
        "......あんまりわかんないケドッ" to "……虽然不太懂啦",
        "がんばりまーす!!" to "我会加油的！！",
    )

    @Test
    fun compareOcrPunctuationHandling() = runBlocking {
        // Downloading ja and zh happens on the first call and takes as long as
        // the network takes. Timed and reported, but never counted as
        // translation latency.
        val downloadStarted = System.currentTimeMillis()
        // The generous timeout has to be the *inner* one: a `withTimeout`
        // around a call that has its own shorter one is decided by the shorter.
        translate("こんにちは", DOWNLOAD_TIMEOUT_MS)
        println("CLEAN_MT models ready in ${System.currentTimeMillis() - downloadStarted}ms")
        println("CLEAN_MT engine=mlkit ja>zh, 15 bubbles as manga-ocr read them")

        val empties = mutableMapOf<String, Int>()
        val totals = mutableMapOf<String, Long>()

        for ((source, reference) in bubbles) {
            println("CLEAN_MT ---")
            println("CLEAN_MT   ref       $reference")

            for ((variant, run) in variants) {
                val started = System.currentTimeMillis()
                val output = run(source)
                val elapsed = System.currentTimeMillis() - started

                totals[variant] = (totals[variant] ?: 0) + elapsed
                if (output.isBlank()) empties[variant] = (empties[variant] ?: 0) + 1

                println("CLEAN_MT   $variant ${elapsed}ms  \"$output\"")
            }
        }

        println("CLEAN_MT === summary over ${bubbles.size} bubbles ===")
        for (variant in variants.keys) {
            println(
                "CLEAN_MT   $variant empty=${empties[variant] ?: 0}" +
                    " total=${totals[variant]}ms",
            )
        }
    }

    /**
     * Keyed by name so the summary and the per-bubble lines cannot disagree
     * about which variant produced what.
     */
    private val variants: Map<String, suspend (String) -> String> = linkedMapOf(
        "raw       " to { source -> translate(source) },
        "normalised" to { source -> translate(OcrPunctuation.normalize(source)) },
        "stripped  " to { source ->
            val affixed = OcrPunctuation.split(OcrPunctuation.normalize(source))
            affixed.reattach(translate(affixed.core))
        },
        // Suggested by the previous run rather than planned: stripping the ends
        // fixed `……あ` but left `目を…閉じて` as one string, which came back
        // having lost the eyes. Cutting at every ellipsis costs one call per
        // fragment, which is why the latency column matters here.
        "fragmented" to { source ->
            OcrPunctuation.rejoin(
                OcrPunctuation.fragments(source).map { fragment ->
                    if (fragment.isBlank()) fragment else translate(fragment)
                },
            )
        },
    )

    private suspend fun translate(text: String, timeoutMs: Long = TIMEOUT_MS): String {
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

    private companion object {
        const val TIMEOUT_MS = 30_000L
        const val DOWNLOAD_TIMEOUT_MS = 600_000L
    }
}
