package com.babel.domain.game

import com.babel.core.model.CoordinateSpace
import com.babel.core.model.LanguageTag
import com.babel.core.model.Revision
import com.babel.core.model.SourceIdentity
import com.babel.core.model.SourceStyle
import com.babel.core.model.TextBounds
import com.babel.core.model.TextElement
import com.babel.core.model.TextElementId
import com.babel.core.model.TextSourceType
import com.babel.core.model.TranslationRequest
import com.babel.domain.vision.JapaneseScript

/** Normalizes settled [GameLine] readings into pipeline [TextElement]s. */
object GameElements {

    /** Kana, decayed over recent frames, that make a game a Japanese one. */
    const val JAPANESE_GAME_KANA = 20.0

    val JAPANESE = LanguageTag("ja")

    fun toElement(
        key: String,
        line: GameLine,
        style: SourceStyle,
        conversational: Boolean,
        window: TextBounds,
        packageName: String,
        session: Long,
        kanaVotes: Double,
        speaker: Boolean = false,
        row: List<String> = emptyList(),
    ): TextElement {
        val b = line.bounds
        val grow = style.backdrop?.marginPx ?: 0
        val growRows = style.backdrop?.rowMarginPx ?: 0
        return TextElement(
            id = TextElementId("game:${key.hashCode().toUInt().toString(16)}"),
            text = line.text,
            // Grown by the erase margin when there is one, so the cleared
            // patch takes in the glyphs' outline.
            bounds = TextBounds(
                b.left - grow + window.left,
                b.top - growRows + window.top,
                b.right + grow + window.left,
                b.bottom + growRows + window.top,
                CoordinateSpace.SCREEN,
            ),
            sourceType = TextSourceType.GAME,
            // Dialogue carries the lines before it as context; a label does
            // not. A menu's 「エピソードリスト」, given the story's lines as
            // context, came back as the chapter's title.
            conversational = conversational,
            recognised = true,
            carriesContext = true,
            // A game's name or interface word, worth remembering across restarts:
            // a label by its words — no sentence punctuation, a label's length —
            // so a line of dialogue is never written to disk.
            durableLabel = GameLayout.isLabel(line.text),
            keepsLatinWords = true,
            source = SourceIdentity(packageName = packageName),
            revision = Revision(session),
            sourceLanguage = JAPANESE.takeIf {
                JapaneseScript.isJapanese(line.text) ||
                    (kanaVotes >= JAPANESE_GAME_KANA && JapaneseScript.couldBeJapanese(line.text))
            },
            style = style,
            // A name over dialogue is sent as a name: alone, 「ヒナ」 came back
            // as 「雏」 ("chick") where beside its affiliation it was 「日奈」.
            // And the game it is from, so names and terms come back as that
            // game's players know them: 「チナツ」, alone, was 「千夏」 one day
            // and 「奇娜」 the next.
            metadata = buildMap {
                put(TranslationRequest.Hints.GAME, packageName)
                if (speaker) put(TranslationRequest.Hints.SPEAKER, "true")
                if (row.isNotEmpty()) put(TranslationRequest.Hints.ROW, row.joinToString("\n"))
            },
        )
    }
}
