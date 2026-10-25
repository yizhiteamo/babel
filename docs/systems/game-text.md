# Game text: what is translated

Game mode decides for itself what on a game's screen to translate. Nothing is configured per game or per scene: the decision is made from signals that every game's screen shows. When a scene is misjudged, the fix is a better general rule, checked against every known scene. A per-game exception is never the fix.

The code is `GameTextFilter` (verdicts per line), `GameLayout` (layout rules) and `StableLines` (stability over time), called from `GameTextSource`.

## The rule set

A line is translated when it is **said**: dialogue, narration, a chat message, a choice, a pop-up's or a detail panel's sentence (a skill's description). The speaker's name over any of these is translated too.

The interface is translated **only where the reader cannot read it**: a label written mostly in kana (「ステータス」, 「ノーマルスキル」, 「アタッカー」, a student's name), on a plain panel, in full view. Labels in kanji (「攻撃力」, 「固有武器」, 「基本情報」) are left as they are, because a reader of Han reads them. Numbers, stats, Latin controls and badges are never translated. (This replaced the earlier "story only" decision on 2026-10-14: a screen of katakana game terms was closed to a Chinese player, while its kanji labels were not.)

Nothing is translated **behind a dimmer**. When a pop-up opens, the screen under it darkens, and none of that screen is translated: a line in which nothing is bright (95th-percentile brightness under 120) is dimmed. Measured on dimmed white type: 44–83.

The decision runs in four stages.

### 1. What can be read at all

`GameTextFilter.judge` gives each line a verdict:

- **Numbers and figures** are never translated. This covers whole numbers, and lines with more digits than half their letters, such as dates and stats (`GameLayout.figures`).
- **Lettering on art** (`ON_ARTWORK`) is not translated. This is decided by how well the panel around the line is predicted from its two ends (`backdropError`).
- **Type too large** (`TOO_LARGE`) is a logo or title art, and is not translated.
- **Type too small** (`TOO_SMALL`) is not translated on its own, only as part of a paragraph.
- **Controls** are a game's own words in another script: mostly-Latin words in a Japanese game (AUTO, MENU, MomoTalk). They are never translated.
- **Two labels read as one** are cut apart where the ink stops for a type height or more (a word's glyphs stand a fraction apart), or at a divider read as `|`; each piece gets the letters its share of the ink holds. Two tabs' 「ノーマルスキル」「パッシブスキル+」 had been one squeezed translation across both tabs. Not for anything with sentence marks or spaces.
- **Icons read as characters** are taken off a line's start: 「◆アタッカー」, a badge 「④」, or a badge's number glued to a name in katakana (「9ハナコ」). Their place still bounds the patch, so the icon is not painted over.
  - A sign before Japanese (「@ナギサ」, the affection badge read as `@`) is always an icon; a quote or bracket is not.
  - A Latin letter before Japanese (「Cウイ」) is an icon only where the first glyph's ink is another colour than the rest's (the badge's pink against a white name measured 111 of 441). 「Cランク」 keeps its C.
  - A kana before Japanese (「ワホシノ」, 「マアル」) is an icon only where its ink is another colour **and**, read alone at twice the size, it is the badge's number; what stands beside it is read again on its own and taken when it gives a letter back (「ハナコ」, which had lost its 「ハ」 to the badge's box).
  - An icon of another colour just left of a line's box, unread, still bounds the patch: 「ユズ」's patch had grown over her badge and painted it out.
  - A role icon read as one hiragana before a katakana word (「あサポータ」, 「もサポーター」) is taken off.
  - Icons inside a line cut it there: 「(臨戦)★食★STRIKE」 is 「(臨戦)」.
- **Two pieces of text in one recognised line** — a carousel's front title and the end of the title behind it (「第2部メインストーリー」, 「トリー」) — are cut apart where the characters stand more than 0.6–0.7 of their height apart, or sit on another baseline; each piece needs two letters, so a label set wide (「サポータ ー」) stays whole.
- **Pieces of one sentence cut apart** — touching on a row, one type size, one of them a sentence — are joined again, and a 「?」 straight before a particle is dropped as half a cut 「を」. A skill description's line 「…攻撃速度を30.6%増加…」 had come back as 「…攻撃速度?」 and 「を30.6%…」, translated apart, and the paragraph no longer joined line to line. A line with figures is never cut by a change of type either: its numbers are set larger and in blue.
- **A coloured mark inside a line's box** before its ink — a pop-up title's pale accent bar — bounds the patch, and strokes read off it (「||」) are taken off the text.
- **A skill's description** is said even when short and unpunctuated: a line with 「を」 or 「が」 between two words (「攻撃力を542増加/」) is a sentence. Pieces cut at a blue figure are joined again (a figure and its unit, words and the figure after them), and a line with figures is read again on its own — then at twice the size — when that gives back a digit (「1955」 had been read 「955」).
- **A pop-up's title** over its skill label, on the same left edge (within two glyphs), is translated with it; a line with a percentage or ending in 「/」 is never a name.
- **Bars before a line** (「||」, or 「一」 where a coloured accent bar stands) are taken off, sentence or not.
- **A bracketed part** after a word on its row is joined to it (「ハナコ」「(水着)」 → 「ハナコ(水着)」), so the name and its outfit are one label.
- **A filled 「?」 at a line's end** is a help button, not the word's question: 「生徒?」 is 「生徒」, and the button is not painted over.
- **A row of small labels with a gap** where a label should be: what is in the gap is read again at twice the size. 「ノーマルスキル」 in a skill card was left out on three students of five.
- **A lost first letter**: a word cannot begin with a lengthening mark, so a label read 「-マルスキル」 is read again on its own at twice the size, which gave back 「ノーマルスキル」; when it still begins with the mark it is left untranslated. It had been translated 「玛尔斯技能」, Mars skill.
- **A label and what it labels read as one**: a line whose type changes size and colour together across a gap is cut there, and each piece read again on its own. A skill pop-up's small blue-grey 「ノーマルスキル」 in its card and the skill's large dark name beside it came back as 「ノーマルスキル決定的な手がかり」 and were painted as one across both. The sizes are compared a few glyphs either side of the gap (measured 0.40 against a speaker's katakana name and kanji affiliation's 0.75–0.78), and never in a line with sentence marks: a highlighted word in a sentence is another colour too.

### 2. What is said: seeds

A line is dialogue on its own when any of these holds:

- **It is a sentence.**
  - Punctuation with four letters or more counts. A badge does not: kanji alone with a `!` (「演習開始!」), or a word with only a stray `、` after it.
  - A question with two letters or more counts, with its mark against the word: in 「生徒 ?」 the mark is a help button.
  - An ellipsis after two letters or more counts.
  - A full stop after two letters or more counts under a name set no smaller: 「……モモカ。」, read without its ellipsis, is a line that is only a name. No interface word is set with a full stop.
  - Length counts too: more than twelve letters, unless it is labels spaced apart, or fewer than five Latin words (「Enter the Dungeon」 is a button).
- **It is in a place a conversation has been** in the last minute: a dialogue box and its name tab.
- **It is under a name said twice or more on the screen**, such as a chat sender over each run of messages.
  - The line must be in a message's type size, not smaller.
  - It must not be figures.
- **It is in a chat column seen lately.**
  - A column is three lines or more with one left (or right) edge and one type size.
  - At least two of those lines must be sentences or messages under a name.
- **It is the player's reply.** That is, it stands right of a chat column, on the same page:
  - the row from the column to the line crosses at most two edges;
  - the colour just before and just after the line is the page's colour.

### 3. What goes with what is said: spread

From the seeds, dialogue spreads to:

- **Pieces on the same row**, near along it.
- **Wrapped lines** above or below. Both must be on one panel, with the gap between them in the upper line's own colour. Inside a chat column, both lines must be in the column.
- **Lines in the same column**: the same left edge, or the same right edge where two sentences already share it. They must be of one size and within twelve lines. A line with items beside it on its row is a menu, not a column member.
- **Speakers' names** over a spoken line, and a name further above one with nothing read in between (for a first line the recogniser dropped). Names spread no further.

Afterwards these are removed: a line in a **menu bar** (four labels or more of one size on a row), and **figures**.

A phrase of kanji held together by hiragana (「生徒たちの日常」) is a label the reader cannot read, even with less than half kana: its grammar is the unreadable part. A kanji label (「装備強化」) has no hiragana.

A label already translated on a plain panel is translated on a translucent one too (backdrop error up to 28), so a tab over a student's blurred art reads as it does on the next student's page.

Afterwards, an interface label must also be one word's script: letters that go from Latin to Japanese and back twice or more (「フA/のA0」, the dimmed top bar's 「74/240」 misread behind a pop-up) are not a label.

### 4. Over time

When the scene changes (most of the picture changes at once, such as a pop-up opening or closing), everything remembered of where dialogue stood is forgotten: dialogue places, chat columns and senders. Kept after a skill pop-up closed, the pop-up's place had the status screen's labels under it translated as dialogue for a minute, until they vanished.

`StableLines` publishes a line once two readings agree. A line known already is published at its first reading.

- **Unchanged:** a line that stood still between two pictures is published at its first reading.
- **Typing:** a line is not published while letters still appear after it, however often it reads the same; the comparison is piece by piece (two type heights wide), since a few letters are too little of a wide box to move its mean. For dialogue the room is the box's, from where its lines begin to the box's right and two lines down; for a phrase with hiragana not (yet) dialogue, the two type heights after it on its row. While a line types, readings are whole, never in parts, since a part can begin inside a word. Both stop after 4 s, so art moving under a translucent box cannot hold a line for ever. Without this, a line read in bands while typing settled as three pieces, each seen twice, with the original between them.
- **Misread names:** a name read one character off a known one is snapped to it, but only where the two characters are ones the recogniser confuses (ツ/ッ, カ/力, ー/一, and so on). 「アカネ」 is not 「アカリ」.
- **Withdrawn:** a published line is withdrawn when its place goes blank, when other words are read there, or when the picture shows its place changed.
- **Held:** a published line whose place looks as it did when it was published is never withdrawn by a miss or by another reading of the same words (mostly the same characters): its words cannot have changed if its pixels did not. On a translucent panel over a waterfall, 「サポーター」 read 「あサポータ」 every few frames and went missing on others, and its translation went and came back every few seconds — 18 withdrawals a minute on a screen nobody touched, none after.
- **Moved:** a line found again a little up or down is moved, not withdrawn.

## How a rule is changed: the scene corpus

`GameCorpusTest` (an instrumented test) reads every scene in `material-sample/game-corpus/` at once.

- **Scenes:** each scene is a clean screenshot, taken with game mode off.
- **Expectations:** each scene has a `.expect` file with one rule per line:
  - `+ words`: some translated line must hold these words.
  - `- words`: no translated line may hold them.
- **Output:** for every line read, the test prints whether it was kept, dialogue or a speaker, and which rule took it in (`sentence`, `voiced`, `column`, `reply`, `wrap:…`, `aligned`).

The corpus is not committed: it is screenshots of games (`material-sample/` is ignored). Push it to the test app's `files/game-corpus/` directory on the phone and run:

```
./gradlew :platform:game:assembleDebugAndroidTest
adb push material-sample/game-corpus/. /sdcard/Android/data/com.babel.platform.game.test/files/game-corpus/
adb shell am instrument -w -e class com.babel.platform.game.GameCorpusTest com.babel.platform.game.test/androidx.test.runner.AndroidJUnitRunner
adb logcat -d | grep GAMECORPUS
```

To change a rule:

1. **Add the scene.** Every misjudgment met in play becomes a scene in the corpus, with its expectation, before it is fixed.
2. **Fix by a general signal.** Find the rule that took the line in (or left it out), and change that rule using a signal every game shows. A game's or a scene's name is never the signal.
3. **Keep every scene passing.** All scenes must pass, not only the new one.

Scenes so far (2026-10-13):

- **Blue Archive:** the chat list, five chats (including scrolled ones), the student list and profile, a student's status screen and two skill pop-ups, the home screen, and story frames.
- **Fate/Grand Order:** a servant card, the title screen, and story frames.
- **Shattered Pixel Dungeon:** the title menu.

Added 2026-10-18: the student list and thirteen students' status pages (Hanako, Hina, Hoshino twice, Mika, Wakamo, Hifumi, Mine, Aru, Toki, Hibiki, Iroha, Izuna, Kanna, Yuzu, Rio) — badges, outfits in brackets, role icons, skill labels.

Added 2026-10-17: two students' status screens (an affection badge before the name, 「ノーマルスキル」 read 「-マルスキル」) and a skill-growth pop-up (labels read together with the skill names beside them, the dimmed top bar behind).

Skill pop-ups: 49 pop-ups of 13 students, every line of each checked (`ba-pop*`). All 498 expectations pass (2026-10-18). Three are kept as notes rather than checks: labels the recogniser does not read at all on that one picture, or translucent tabs translated only once known from another page.

A translation that lacks one of the original's figures is asked again without context: a skill's 「味方の攻撃力を17.3%増加」 came back as the previous pop-up's 「再增加26.6%的治疗力」 (`EchoGuard.losesFigures`). An answer with English words in it, for a Chinese reader, is asked again too (`SameLanguageGuard`).

**Colour.** A line with figures in a second ink — a skill's numbers in blue among grey words — is measured as two inks (`SourceStyle.accentColor`), and its translation's figures are set in the second. Every figure of the translation takes it: where the original left one figure grey, the translation does not know which.

**A whole reading is taken when due, changed or not.** A reading taken while a screen slid in (a title's box taking in the back button, read as lettering on art) had been offered again on every still frame after; the page's title was never translated.

**Still pictures are a moment apart.** Two pictures say a line stood still only when taken within 0.8 s: paced slowly on a still screen, a tap to the next student made a picture mid-slide match one from before the tap, and a skill button's label was published where it stood mid-slide, out of place.

**A row of labels is set alike.** Labels of one size on one row share one weight and one ink, remembered for the row's place: measured alone, a skill card's labels came out bold and regular side by side (strokes 0.15–0.20 across the renderer's steps), and 「EXスキル」 under the selected card's glow a deeper blue. A label on art (backdrop up to 28) is translated when two of its row are. A button of two stacked words, one of them kanji (「スキル」 over 「成長」), is left whole, like 「固有武器」.

## How a label is translated

Every label is translated through its words, one request per word at a time, with the other labels of its row sent as reference (`Hints.ROW`) when the row holds three or more and the label is a term without hiragana — a phrase given its neighbour came back as a paraphrase of it — alone, 「ノーマルスキル」 came back 「技能宝珠」 and 「普通技能也」: a label with its 「+」 and the same label without it, asked together, came back 「普通技能+」 and 「技能宝珠」. A known label read with 「ナ」 for its 「+」, or with the other voicing mark, is the known label; a label without the 「+」 another card has keeps its own words.

A label made of a word and a bracketed part (「ハナコ(水着)」), or of a word and a trailing 「+」 (「ノーマルスキル+」), is translated as its words and put back together, with the target language's brackets (`BracketedLabel`). Each word is cached under the key the same word alone would have, so a student's name reads the same in her card and on her page, and a skill label the same with or without its 「+」. Sent whole, one screen's names had come back 「花子(泳装)」, 「星野（泳装）」 and 「泳装小优」. A kana word that comes back unchanged sends the label whole instead.
