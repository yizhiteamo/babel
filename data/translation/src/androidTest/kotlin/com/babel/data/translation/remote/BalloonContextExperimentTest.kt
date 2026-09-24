package com.babel.data.translation.remote

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.babel.core.model.ApiKey
import com.babel.core.model.LanguagePair
import com.babel.core.model.LanguageTag
import com.babel.core.model.ProviderId
import com.babel.core.model.RequestId
import com.babel.core.model.Revision
import com.babel.core.model.SourceLanguageMode
import com.babel.core.model.TargetLanguageMode
import com.babel.core.model.TextElementId
import com.babel.core.model.TranslationRequest
import com.babel.domain.settings.BabelSettings
import com.babel.domain.settings.RemoteProviderSettings
import com.babel.domain.settings.RemoteService
import com.babel.domain.settings.SettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith

/**
 * One sentence split across several balloons: is context enough, or does it
 * have to be joined?
 *
 * Measured on page 07, where the detector was cleared of any fault and the real
 * problem turned out to be the unit of translation. `PageTextDumpTest` read it
 * with manga-ocr and the balloons are:
 *
 * ```
 * わたしの   めを見て   わたしの   めを見て   わたしの
 * ```
 *
 * `わたしの めを見て` — *look at my eyes* — cut in half, twice. Translated one
 * balloon at a time it comes out as 我的 / 看着眼睛.
 *
 * ## Why the obvious fix was dropped
 *
 * Giving each balloon the **preceding** ones as context keeps publishing
 * incremental, and was the first proposal. It is wrong for this language:
 * Japanese is verb-final and the particle that fixes the meaning arrives at the
 * end, so `わたしの` alone is decided by what comes *after* it. Prefix context
 * helps the half that already reads.
 *
 * So both candidates need the page read first, and the question is which:
 *
 * | variant | what the engine gets |
 * |---|---|
 * | alone | the fragment, nothing else — today's behaviour |
 * | context | the fragment, with the whole group in DeepL's `context` |
 * | joined | the group run together as one string, translated once |
 *
 * `joined` is the better translation by construction; what it costs is a
 * pipeline where one request covers several elements and the answer has to be
 * split back across their boxes. `context` costs almost nothing — the field
 * already exists on `TranslationRequest` and DeepL does not bill for it. So
 * this exists to find out whether the cheap one is close enough.
 *
 * Complete balloons are included as a control. If `context` degrades those, it
 * cannot be turned on for the whole page.
 *
 * Prints, asserts nothing. Translation quality is a judgement, like every other
 * translation experiment here.
 *
 * ## Running it
 *
 * Needs a real DeepL key, which is never committed and never printed:
 *
 * ```
 * KEY=$(adb shell run-as com.babel cat files/datastore/babel_settings.preferences_pb \
 *   | tr -d '\0' | grep -oE '[0-9a-f-]{36}(:fx)?')
 * adb shell am instrument -w -e deeplKey "$KEY" -e class \
 *   com.babel.data.translation.remote.BalloonContextExperimentTest \
 *   com.babel.data.translation.test/androidx.test.runner.AndroidJUnitRunner
 * adb logcat -d | grep CTXEXP
 * ```
 *
 * The Japanese below is copyrighted dialogue, held here the way
 * `BubbleScoring.groundTruth` already holds transcriptions: in the test source
 * set, never in the app, and the pages themselves never committed.
 */
@RunWith(AndroidJUnit4::class)
class BalloonContextExperimentTest {

    /**
     * Groups of balloons in reading order, as manga-ocr read them.
     *
     * A group of one is a complete balloon and is the control.
     */
    private val groups: List<Pair<String, List<String>>> = listOf(
        "07 split 1" to listOf("わたしの", "めを見て"),
        "07 split 2" to listOf("わたしの", "めを見て"),
        "01 split 1" to listOf("はい", "いくらでも使ってください"),
        "01 split 2" to listOf("......あ", "そっちは私の使用済み..."),
        "01 split 3" to listOf("......先生?", "先生?"),
        "01 whole" to listOf("先生も汗拭きシート使いますか?"),
        "04 whole" to listOf("目を...閉じて..."),
        "04 whole 2" to listOf("...絶対に...動かないこと..."),
    )

    private val languages = LanguagePair(
        source = LanguageTag("ja"),
        target = LanguageTag("zh-Hans-CN"),
    )

    @Test
    fun compareAloneAgainstContextAgainstJoined() = runBlocking {
        val key = InstrumentationRegistry.getArguments().getString("deeplKey").orEmpty()
        if (key.isBlank()) {
            println("CTXEXP skipped: pass -e deeplKey <key>")
            return@runBlocking
        }

        val translator = DeepLTranslator(
            settingsRepository = KeyOnly(key),
            logger = com.babel.core.common.BabelLogger.NoOp,
        )

        var id = 0
        for ((label, fragments) in groups) {
            println("CTXEXP")
            println("CTXEXP === $label ===")
            println("CTXEXP   source: ${fragments.joinToString(" | ")}")

            // The whole group is the context for each of its members, the
            // member itself included — DeepL is documented to take surrounding
            // text, and the fragment's own place in it is part of what it says.
            val wholeGroup = fragments.joinToString("")

            for (fragment in fragments) {
                // Two shapes, because the documentation does not say which it
                // wants and the difference could decide the question: the whole
                // group including this fragment, and only what surrounds it.
                val neighbours = fragments.filterNot { it === fragment }.joinToString("")
                println("CTXEXP   alone      $fragment -> " + translate(translator, ++id, fragment, null))
                println("CTXEXP   ctx-whole  $fragment -> " + translate(translator, ++id, fragment, wholeGroup))
                println("CTXEXP   ctx-around $fragment -> " + translate(translator, ++id, fragment, neighbours))
            }

            if (fragments.size > 1) {
                val joined = translate(translator, ++id, wholeGroup, null)
                println("CTXEXP   joined  $wholeGroup -> $joined")
            }
        }
    }

    private suspend fun translate(
        translator: DeepLTranslator,
        id: Int,
        text: String,
        context: String?,
    ): String {
        val result = translator.translate(
            TranslationRequest(
                requestId = RequestId("ctx-$id"),
                elementId = TextElementId("ctx-$id"),
                revision = Revision(0),
                sourceText = text,
                languages = languages,
                context = context,
            ),
        )
        return "${result.translatedText}  [${result.status}]"
    }

    /**
     * Just enough of a repository to carry a key.
     *
     * `:core:testing` is wired into unit tests only, and widening that for one
     * experiment would be a larger change than the experiment.
     */
    private class KeyOnly(key: String) : SettingsRepository {
        override val settings: Flow<BabelSettings> = flowOf(
            BabelSettings(
                remote = RemoteProviderSettings(
                    service = RemoteService.DEEPL,
                    deepLKey = ApiKey(key),
                ),
            ),
        )

        override suspend fun setSourceLanguageMode(mode: SourceLanguageMode) = Unit
        override suspend fun setTargetLanguageMode(mode: TargetLanguageMode) = Unit
        override suspend fun setProvider(provider: ProviderId?) = Unit
        override suspend fun setRemoteProvider(settings: RemoteProviderSettings) = Unit
        override suspend fun setAutoStart(enabled: Boolean) = Unit
    }
}
