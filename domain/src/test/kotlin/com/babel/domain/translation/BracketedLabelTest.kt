package com.babel.domain.translation

import com.babel.core.model.LanguageTag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class BracketedLabelTest {

    @Test
    fun `a name and its outfit are two words`() {
        assertEquals("ハナコ" to "水着", BracketedLabel.split("ハナコ(水着)"))
        assertEquals("ホシノ" to "臨戦", BracketedLabel.split("ホシノ（臨戦）"))
    }

    @Test
    fun `a line that is not a word and a bracketed part stays whole`() {
        assertNull(BracketedLabel.split("（心の声）"))
        assertNull(BracketedLabel.split("ネル"))
        assertNull(BracketedLabel.split("プレイガイド(1/5)のあと(2)"))
        assertNull(BracketedLabel.split("そうなの？(笑)"))
    }

    @Test
    fun `the brackets are the target language's`() {
        assertEquals("花子（泳装）", BracketedLabel.join("花子", "泳装", LanguageTag("zh")))
        assertEquals("Hanako (Swimsuit)", BracketedLabel.join("Hanako", "Swimsuit", LanguageTag("en")))
    }

    @Test
    fun `a label's trailing plus is a mark, not a word`() {
        assertEquals("ノーマルスキル" to "+", BracketedLabel.marked("ノーマルスキル+"))
        assertNull(BracketedLabel.marked("ノーマルスキル"))
        assertNull(BracketedLabel.marked("+"))
        assertEquals("ノーマルスキル" to "+", BracketedLabel.marked("ノーマルスキルも+"))
        assertEquals("ノーマルスキル" to "+", BracketedLabel.marked("ノーマルスキルナ+"))
        assertEquals("ノーマルスキル" to "+", BracketedLabel.marked("ノーマルスキル①+"))
        assertEquals("EXスキル" to "+", BracketedLabel.marked("EXスキル+"))
    }
}
