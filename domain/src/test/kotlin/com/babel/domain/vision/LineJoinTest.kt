package com.babel.domain.vision

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * What belongs between two recognised lines.
 *
 * The English cases are the real line sequences `PageTextDumpTest` read off
 * `jap-mag-08`; the Japanese ones are what must keep behaving exactly as they
 * did, because every page of the sample material depends on it
 * (`docs/milestones/v2.md`).
 */
class LineJoinTest {

    @Test
    fun `the measured english balloon comes back as sentences`() {
        val lines = listOf(
            "Ấnd she's going",
            "Yto be a Great Witch,",
            "apparently.",
            "A succubus who",
            "can't read a room",
            "to save her life",
            "you say hi and she",
            "starts lecturing you",
            "Vabout magical theor",
        )
        // Joined with nothing this was `...whocan't read a roomto save her
        // lifeyou...`, and the translator was guessing at words that were never
        // on the page. The stray Ấ, Y and V are the recogniser's and stay.
        assertEquals(
            "Ấnd she's going Yto be a Great Witch, apparently. A succubus who " +
                "can't read a room to save her life you say hi and she " +
                "starts lecturing you Vabout magical theor",
            LineJoin.join(lines),
        )
    }

    @Test
    fun `another measured balloon from the same page`() {
        val lines = listOf(
            "Shame, really.",
            "She's got a cute",
            "little face on her.",
            "Letting it go",
            "moldy ina pile",
            "of books.",
            "What a waste.",
        )
        // `ina` is the recogniser's own error and is left alone — this fixes
        // the seams, not the reading.
        assertEquals(
            "Shame, really. She's got a cute little face on her. " +
                "Letting it go moldy ina pile of books. What a waste.",
            LineJoin.join(lines),
        )
    }

    @Test
    fun `vertical japanese columns still run together`() {
        // One sentence split down two columns. A space here would be wrong, and
        // this is the behaviour every Japanese page in the sample relies on.
        assertEquals("わたしのめを見て", LineJoin.join(listOf("わたしの", "めを見て")))
        assertEquals(
            "先生も汗拭きシート使いますか",
            LineJoin.join(listOf("先生も汗拭き", "シート使いますか")),
        )
    }

    @Test
    fun `horizontal japanese gets no space either`() {
        // Page 06's profile block is horizontal, which is why the rule cannot
        // be about orientation.
        assertEquals(
            "白タイツフルーツタルトが大好物",
            LineJoin.join(listOf("白タイツ", "フルーツタルトが大好物")),
        )
    }

    @Test
    fun `a seam with japanese on one side only takes no space`() {
        assertEquals("データbanana", LineJoin.join(listOf("データ", "banana")))
        assertEquals("bananaデータ", LineJoin.join(listOf("banana", "データ")))
    }

    /**
     * The defect this was reported as: `선생님` welded to `줄게`.
     *
     * The joined run went to the translator as `선생님줄게` and came back as
     * the personal name 徐世尼 — a title read as a name because the words had
     * been welded together first. Hangul used to sit in the no-space set beside
     * Han and kana, which is simply wrong about the language.
     */
    @Test
    fun `korean writes spaces between words`() {
        assertEquals("선생님 줄게", LineJoin.join(listOf("선생님", "줄게")))
    }

    /** The rest of `kr-mag-01`'s multi-line balloons, as the engine read them. */
    @Test
    fun `measured korean balloons come back spaced`() {
        assertEquals(
            "아이스크림 가거를 사주고 싶어",
            LineJoin.join(listOf("아이스크림", "가거를", "사주고 싶어")),
        )
        assertEquals(
            "정확히, 반으로 가를거니까",
            LineJoin.join(listOf("정확히,", "반으로", "가를거니까")),
        )
    }

    /**
     * A seam with Latin on one side.
     *
     * This used to assert `banana데이터` — the wrong behaviour written down as
     * expected, and filed under "japanese on one side" although `데이터` is not
     * Japanese at all. Two words in two space-writing scripts take a space.
     */
    @Test
    fun `a korean and latin seam takes a space`() {
        assertEquals("banana 데이터", LineJoin.join(listOf("banana", "데이터")))
        assertEquals("데이터 banana", LineJoin.join(listOf("데이터", "banana")))
    }

    /**
     * Korean meeting Han or kana still joins with nothing.
     *
     * The rule is unchanged — either side writing without spaces is enough —
     * and only Hangul's membership moved. Pinned so that the fix is understood
     * as narrow rather than as "Korean always takes a space".
     */
    @Test
    fun `korean against a non-spacing script still joins with nothing`() {
        assertEquals("데이터データ", LineJoin.join(listOf("데이터", "データ")))
        assertEquals("先生선생님", LineJoin.join(listOf("先生", "선생님")))
    }

    @Test
    fun `cjk punctuation at a seam takes no space`() {
        assertEquals("いいの?そうか", LineJoin.join(listOf("いいの?", "そうか")))
        assertEquals("終わり。またね", LineJoin.join(listOf("終わり。", "またね")))
    }

    @Test
    fun `existing whitespace is not doubled`() {
        assertEquals("hello world", LineJoin.join(listOf("hello ", "world")))
        assertEquals("hello world", LineJoin.join(listOf("hello", " world")))
    }

    @Test
    fun `blank lines contribute nothing`() {
        assertEquals("hello world", LineJoin.join(listOf("hello", "   ", "world")))
        assertEquals("", LineJoin.join(listOf("", "  ")))
        assertEquals("", LineJoin.join(emptyList()))
    }

    @Test
    fun `one line is itself`() {
        assertEquals("がんばりまーす!!", LineJoin.join(listOf("がんばりまーす!!")))
        assertEquals("Hello", LineJoin.join(listOf("Hello")))
    }
}
