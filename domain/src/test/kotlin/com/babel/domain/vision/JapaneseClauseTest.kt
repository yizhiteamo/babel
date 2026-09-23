package com.babel.domain.vision

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The four cases the DeepL experiment measured, plus the ones that would make
 * the rule dangerous if it got them wrong.
 *
 * Joining is right for a fragment and destructive for a complete utterance —
 * `はい` joined to its neighbour came back with the `はい` gone
 * (`docs/milestones/v2.md`). This is the rule that decides which is which, so
 * the measured cases are guarded rather than merely documented.
 */
class JapaneseClauseTest {

    @Test
    fun `the measured fragments wait for what follows`() {
        assertTrue(JapaneseClause.isUnfinished("わたしの"))
        assertTrue(JapaneseClause.isUnfinished("私はたった今から"))
        assertTrue(JapaneseClause.isUnfinished("目を"))
    }

    @Test
    fun `the measured complete utterances do not`() {
        // Joining any of these to a neighbour loses one of the two lines.
        assertFalse(JapaneseClause.isUnfinished("はい"))
        assertFalse(JapaneseClause.isUnfinished("……あ"))
        assertFalse(JapaneseClause.isUnfinished("……先生?"))
        assertFalse(JapaneseClause.isUnfinished("めを見て"))
    }

    @Test
    fun `a full stop ends it even behind an ellipsis`() {
        assertFalse(JapaneseClause.isUnfinished("動かないこと。…"))
        assertFalse(JapaneseClause.isUnfinished("がんばりまーす!!"))
    }

    @Test
    fun `an ellipsis is a pause and not a stop`() {
        // Manga sets these everywhere; treating one as a sentence end would
        // switch the rule off across most of a page.
        assertTrue(JapaneseClause.isUnfinished("そっちは私の…"))
    }

    @Test
    fun `a bare particle is a misread rather than a clause`() {
        assertFalse(JapaneseClause.isUnfinished("の"))
        assertFalse(JapaneseClause.isUnfinished("を"))
    }

    @Test
    fun `text that is not Japanese is left alone`() {
        // The rule is about kana particles and means nothing without them. A
        // western page reaching the manga path must not be regrouped by it.
        assertFalse(JapaneseClause.isUnfinished("Windsor, is that Samantha?"))
        assertFalse(JapaneseClause.isUnfinished(""))
        assertFalse(JapaneseClause.isUnfinished("……"))
    }
}
