package com.babel.data.translation.remote

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.babel.core.common.BabelLogger
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
 * How far a chat model's answer can be from a translation, measured.
 *
 * A chat endpoint does not only translate — it can decline, and it can explain.
 * Sending the browser's address bar, `file:///sdcard/Download/...`, came back as
 *
 * > I can't access files on your device. Please paste the text from the comic
 * > speech balloon directly into the chat, and I'll translate it into Chinese.
 *
 * and that was drawn, in an opaque box, over the address bar — on every page
 * load. DeepL cannot do this, which is why it never showed until the chat route
 * was actually used (ADR 010, `docs/milestones/v2.md`).
 *
 * A guard has to separate "an answer that is a translation" from "an answer
 * addressed to the reader" **without** a keyword list, which would be
 * English-shaped and would misfire on a line that genuinely says "please". The
 * one signal that is language-neutral is **length**: a refusal explains itself,
 * and is long relative to what it was given.
 *
 * How long is long enough is not something to guess — choosing `SCORE_FLOOR`
 * from a hunch nearly produced the wrong fix once already. So this measures the
 * ratio on real material and the threshold comes from the gap.
 *
 * Prints; asserts nothing.
 *
 * ```
 * RAW=$(adb shell run-as com.babel cat files/datastore/babel_settings.preferences_pb | tr -d '\0')
 * adb shell am instrument -w \
 *   -e chatKey "$(echo "$RAW" | grep -aoE 'sk-[A-Za-z0-9]{20,}' | head -1)" \
 *   -e chatEndpoint "$(echo "$RAW" | grep -aoE 'https?://[A-Za-z0-9./:_-]+' | head -1)" \
 *   -e chatModel "$(echo "$RAW" | grep -aoE 'deepseek-[a-z]+' | head -1)" \
 *   -e class com.babel.data.translation.remote.ChatAnswerExperimentTest \
 *   com.babel.data.translation.test/androidx.test.runner.AndroidJUnitRunner
 * adb logcat -d | grep RATIO
 * ```
 *
 * The Japanese below is copyrighted dialogue, held the way
 * `BubbleScoring.groundTruth` already holds transcriptions: test source set
 * only, and the pages themselves never committed.
 */
@RunWith(AndroidJUnit4::class)
class ChatAnswerExperimentTest {

    /** What the pipeline really sends, in the languages it really sends. */
    private val material: List<Triple<String, String, LanguageTag?>> = listOf(
        // Balloons, ja -> zh. Short, and what a ratio guard must not break.
        Triple("balloon", "わたしの", JA),
        Triple("balloon", "めを見て", JA),
        Triple("balloon", "はい", JA),
        Triple("balloon", "……あ", JA),
        Triple("balloon", "データは集まった", JA),
        Triple("balloon", "先生も汗拭きシート使いますか?", JA),
        Triple("balloon", "周囲が泣いたり、騒いだり逃げ出した時…店主の…言葉や誘導は無視して…", JA),
        Triple("balloon", "がんばりまーす!!", JA),

        // English balloons, which reach the chat route with no source language.
        Triple("balloon-en", "Wait, isn't that Samantha? The big hat?", null),
        // The one that kept `apparently` sitting in the middle of the Chinese.
        //
        // **Exactly as the recogniser produced it**, artefacts and all — the
        // missing spaces, the stray capitals, the sentence cut off mid-word.
        // A tidied-up transcription is a different input and gets a different
        // answer: cleaned up, this balloon translates `apparently` correctly,
        // and on the real string it does not. Measuring the clean version would
        // have reported a fix that the device does not have.
        Triple(
            "balloon-en",
            "Ấnd she's goingYto be a Great Witch,apparently.A succubus who" +
                "can't read a roomto save her lifeyou say hi and shestarts " +
                "lecturing youVabout magical theor",
            null,
        ),

        // V1 prose: the longest legitimate inputs the pipeline produces.
        Triple("prose", "The Lighthouse at Cape Mercy", null),
        Triple("prose", "Posted on a quiet Tuesday morning", null),
        Triple(
            "prose",
            "The keeper had lived alone on the headland for eleven winters, and in " +
                "that time he had learned to read the weather the way other people read " +
                "a newspaper.",
            null,
        ),

        // UI labels, which V1 translates as readily as prose.
        Triple("label", "New tab", null),
        Triple("label", "OK", null),
        Triple("label", "Reading Test", null),

        // The inputs that provoke an answer rather than a translation.
        Triple("provoker", "file:///sdcard/Download/reading-sample.html", null),
        Triple("provoker", "https://example.com/a/b/c?q=1", null),
        Triple("provoker", "127.0.0.1:8080", null),
        // OCR noise, which a chat model may also decide to comment on.
        Triple("provoker", "Andoote:goingobeaGeatWittiParentyAstrerdps", null),
    )

    @Test
    fun measureHowLongAnAnswerGetsComparedToItsSource() = runBlocking {
        val arguments = InstrumentationRegistry.getArguments()
        val endpoint = arguments.getString("chatEndpoint").orEmpty()
        val model = arguments.getString("chatModel").orEmpty()
        if (endpoint.isBlank() || model.isBlank()) {
            println("RATIO skipped: pass -e chatEndpoint and -e chatModel")
            return@runBlocking
        }

        val translator = ChatTranslator(
            settingsRepository = Configured(
                endpoint = endpoint,
                model = model,
                key = arguments.getString("chatKey").orEmpty(),
            ),
            logger = BabelLogger.NoOp,
        )

        println("RATIO kind        src  out  ratio  output")
        var id = 0
        for ((kind, text, source) in material) {
            val result = translator.translate(
                TranslationRequest(
                    requestId = RequestId("ratio-${++id}"),
                    elementId = TextElementId("ratio-$id"),
                    revision = Revision(0),
                    sourceText = text,
                    languages = LanguagePair(source = source, target = ZH),
                ),
            )
            val out = result.translatedText
            val ratio = if (text.isEmpty()) 0.0 else out.length.toDouble() / text.length
            println(
                "RATIO %-11s %4d %4d %6.2f  %s".format(
                    kind,
                    text.length,
                    out.length,
                    ratio,
                    out.replace("\n", " / "),
                ),
            )
        }
    }

    /** Just enough of a repository to carry a configured chat endpoint. */
    private class Configured(
        private val endpoint: String,
        private val model: String,
        private val key: String,
    ) : SettingsRepository {
        override val settings: Flow<BabelSettings> = flowOf(
            BabelSettings(
                remote = RemoteProviderSettings(
                    service = RemoteService.CHAT,
                    endpoint = endpoint,
                    model = model,
                    apiKey = ApiKey(key),
                ),
            ),
        )

        override suspend fun setSourceLanguageMode(mode: SourceLanguageMode) = Unit
        override suspend fun setTargetLanguageMode(mode: TargetLanguageMode) = Unit
        override suspend fun setProvider(provider: ProviderId?) = Unit
        override suspend fun setRemoteProvider(settings: RemoteProviderSettings) = Unit
        override suspend fun setAutoStart(enabled: Boolean) = Unit
    }

    private companion object {
        val JA = LanguageTag("ja")
        val ZH = LanguageTag("zh-Hans-CN")
    }
}
