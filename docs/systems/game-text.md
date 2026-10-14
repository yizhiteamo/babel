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

### 2. What is said: seeds

A line is dialogue on its own when any of these holds:

- **It is a sentence.**
  - Punctuation with four letters or more counts. A badge does not: kanji alone with a `!` (「演習開始!」), or a word with only a stray `、` after it.
  - A question with two letters or more counts, with its mark against the word: in 「生徒 ?」 the mark is a help button.
  - An ellipsis after two letters or more counts.
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

### 4. Over time

When the scene changes (most of the picture changes at once, such as a pop-up opening or closing), everything remembered of where dialogue stood is forgotten: dialogue places, chat columns and senders. Kept after a skill pop-up closed, the pop-up's place had the status screen's labels under it translated as dialogue for a minute, until they vanished.

`StableLines` publishes a line once two readings agree. A line known already is published at its first reading.

- **Unchanged:** a line that stood still between two pictures is published at its first reading.
- **Misread names:** a name read one character off a known one is snapped to it, but only where the two characters are ones the recogniser confuses (ツ/ッ, カ/力, ー/一, and so on). 「アカネ」 is not 「アカリ」.
- **Withdrawn:** a published line is withdrawn when its place goes blank, when other words are read there, or when the picture shows its place changed.
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

All 194 expectations pass (2026-10-14).
