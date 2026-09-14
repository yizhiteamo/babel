package com.babel.data.translation.mlkit

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.babel.core.model.LanguagePair
import com.babel.core.model.LanguageTag
import com.babel.core.model.RequestId
import com.babel.core.model.Revision
import com.babel.core.model.TextElementId
import com.babel.core.model.TranslationRequest
import com.babel.core.model.TranslationStatus
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith

/**
 * What the current translator makes of **clean** Japanese.
 *
 * Every complaint about translation quality so far was measured on text the
 * recogniser had mangled — `先生も比拭きシート` translated to nonsense about
 * teachers, and it was never clear how much of that was the translator's fault
 * rather than its input's. manga-ocr now reads these bubbles exactly, so this
 * feeds the real sentences in and prints what comes out.
 *
 * The point is to find out whether replacing the translation engine is worth
 * considering at all. If clean input is enough, two more models need not be
 * downloaded, shipped and maintained.
 *
 * Prints rather than asserts: translation quality is a judgement, and a single
 * reference translation scored automatically would be a worse guide than
 * reading the output.
 */
@RunWith(AndroidJUnit4::class)
class CleanInputTranslationTest {

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
    fun translateCleanJapanese() = runBlocking {
        println("CLEAN_MT engine=mlkit ja>zh, input as manga-ocr read it")

        for ((source, reference) in bubbles) {
            val started = System.currentTimeMillis()
            val result = withTimeout(TIMEOUT_MS) {
                translator.translate(
                    TranslationRequest(
                        requestId = RequestId("clean"),
                        elementId = TextElementId("e"),
                        revision = Revision(0),
                        sourceText = source,
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
            val elapsed = System.currentTimeMillis() - started

            val output = when (result.status) {
                TranslationStatus.Translated -> result.translatedText
                else -> "<${result.status}>"
            }
            println("CLEAN_MT ${elapsed}ms")
            println("CLEAN_MT   ja  $source")
            println("CLEAN_MT   zh  $output")
            println("CLEAN_MT   ref $reference")
        }
    }

    private companion object {
        const val TIMEOUT_MS = 60_000L
    }
}
