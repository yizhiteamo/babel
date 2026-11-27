# 开发流程

谁做什么、一个改动怎样从任务变成 `main` 上的一个提交。项目规则（架构、模块边界、行为不变量）在 `CLAUDE.md`，本文件只管流程；两边不互相复制。决定依据见 ADR 012。

## 角色

| 角色 | 负责 | 不做 |
|---|---|---|
| 用户 | 定优先级；在 Claude 与 Gemini 之间传话；产品决定（标 `decision-needed` 的 Issue）；**合并 PR**；确认发版 | — |
| Claude（规划/审查） | 从 `docs/milestones/` 拆任务、写 Issue；审查 PR；维护本文件与模板 | 不为 Issue 写实现代码（流程文件除外） |
| Gemini（编码，Antigravity） | 按 Issue 开分支、实现、写测试、本机构建与真机验证、提 PR、按审查修改 | 不合并 PR；不直接推 `main`；不自行扩大 Issue 范围 |

三方共用 GitHub 账号 `yizhiteamo`。GitHub 不允许批准自己的 PR，所以审查结论以评论形式给出，合并由用户点。

## 一个改动的生命周期

### 1. Issue

Claude 用 `.github/ISSUE_TEMPLATE/task.yml` 创建，挂到对应的 GitHub Milestone（如「V3 — 沉浸式游戏翻译」）。

- 一个 Issue = `docs/milestones/` 中的一个条目，或其中一块能单独验收的部分。
- Issue 写清范围、**不做什么**、验收条件、要读的文档、测试要求、是否需要真机。引用文档段落，不复制文档内容。
- 标签：`needs-device-test`（必须有真机证据）、`decision-needed`（等用户拍板，先不实现）、`blocked`（依赖别的 Issue 或外部条件）。

### 2. 分支

从最新的 `main` 切出：`<type>/<issue 号>-<英文短名>`，如 `feat/14-game-language-memory`、`fix/21-chat-scroll-misread`。

type 与提交信息相同：feat / fix / refactor / test / docs / chore。

### 3. 实现

Gemini 动手前按顺序读：`GEMINI.md` → `CLAUDE.md` → 本文件 → Issue 列出的文档（`CLAUDE.md` 的 Scope Discipline 与 Documentation Routing 照常适用）。

- 提交信息：`<type>(<scope>): <中文简述>`，scope 取 domain / data / platform / app / docs 等；正文用要点列出改了什么、为什么。
- 只做 Issue 范围内的事。发现范围外的问题，写进 PR 描述的「后续」，不要顺手改。
- 里程碑条目的勾选与实现放在同一个 PR；只有 `docs/features/` 的验收条件真正满足才勾。
- 行为或架构变了，就更新对应的 `docs/systems/*.md` / ADR；与已接受的 ADR 冲突时，显式修订或取代它，不要绕过。

### 4. 自测

- `./gradlew build` 必须本机通过（含 lint 与全部单元测试）。
- 带 `needs-device-test` 的，或改动涉及无障碍服务、悬浮层、截屏、OCR、游戏模式的：在手机/MuMu 上验证，并跑相关仪器测试（如 `GameCorpusTest`、`GameTextSourceReplayTest`）。
- 真机之前先读 `docs/systems/testing.md` 的陷阱清单。最容易毁掉测量的几条：不要用 `uiautomator dump` 读状态；不要跑 `:app:connectedDebugAndroidTest`（会卸载 App、清掉设置和模型）；不要关掉设备 Wi-Fi；手机发热时不比较速度。

### 5. Pull Request

`gh pr create`，标题与提交信息同格式，正文按 `.github/pull_request_template.md` 填：

- `Closes #<issue>`
- 改了什么、为什么
- 验收条件逐条对照：满足 / 未满足 / 不适用，附依据
- 测试证据：执行的命令与结果摘要；真机测试写设备型号、Android 版本、场景、步骤和观察到的结果
- 文档与里程碑的更新
- 风险与后续

**仓库是公开的。** PR、Issue、评论中：

- 不上传游戏或漫画截图——是别人的版权素材（`material-sample/` 不提交也是这个原因）。用文字描述现象，必要时用 `docs/testing/` 里的自制页面截图。
- 不贴未经 `Redact` 的屏幕文本；日志摘录只贴脱敏过的行。
- 不出现密钥、API key、`local-device-notes.md` 里的内容（其他 App 包名等）。

### 6. CI

`.github/workflows/ci.yml` 在每个 PR 和 `main` 上跑 `./gradlew build`。CI 没有设备和本地素材，所以 **CI 绿不代表真机行为正确**，真机证据仍要写在 PR 里。

### 7. 审查

用户对 Claude 说「审 PR #N」。Claude：

1. `gh pr view N`、`gh pr diff N`、`gh pr checks N`；需要时在独立 worktree 里 checkout 本地构建或跑测试。
2. 按下方清单逐项检查。
3. 用 review API（`event: COMMENT`）发行内评论和一条总评，总评第一行写结论：**「审查通过」** 或 **「需修改」**，后者列出必须修改的项（阻塞）与建议（非阻塞）。

审查清单：

- **范围**：只做了 Issue 要求的事；没有提前实现未来阶段的功能、引入无用依赖或空壳抽象。
- **模块边界**：没有为图方便加跨层依赖；平台类型没有漏进 `:core:*` / `:domain`。
- **行为不变量**（`CLAUDE.md`「Contracts already established」）：Revision 比较、`Translator` 不抛异常、缓存 key、`Redact`、协调器只在 mailbox 改状态、扫读记忆的规则等，没有被破坏。
- **测试**：覆盖行为与状态转换，而不只是覆盖代码行；修 bug 时有能复现该 bug 的测试或语料场景（游戏误判先进 corpus，见 `docs/systems/game-text.md`）。
- **隐私**：敏感字段不翻译不记录；文本只经翻译层离开设备。
- **文档**：行为/架构改变时相应文档已更新；里程碑勾选有验收依据。
- **证据**：真机证据可信、可复现，符合 `testing.md`；声称「已验证」的地方确有对应记录。

### 8. 修改

Gemini 在同一分支**追加**提交回应审查，不要在审查期间 force-push（会让已有评论失去位置、复审看不出改了什么）。改完在 PR 下回复每条阻塞意见如何处理，用户转告 Claude 复审。

### 9. 合并

条件：CI 绿 + Claude 总评「审查通过」+ 无未解决的阻塞意见。用户用 **Squash and merge** 合并；合并后分支自动删除。一个 PR 最终在 `main` 上就是一个提交，标题即 PR 标题。

## 不走 PR 的例外

- 发版：按 `docs/systems/release.md`，只推 tag，不推 `main`。
- 用户明确要求的紧急操作。其余一律走 PR——`main` 受分支保护，管理员也不能直接推。

## 给 Antigravity 的启动提示词

把 `<N>` 换成 Issue 号后整段粘贴：

```text
你在 Babel 项目中担任编码方。请实现 GitHub Issue #<N>。

1. 先读 GEMINI.md、CLAUDE.md、docs/workflow.md，再用 `gh issue view <N>` 读 Issue，并读 Issue 中列出的文档。
2. 从最新 main 切出分支：<type>/<N>-<英文短名>。
3. 只实现 Issue 范围内的内容；范围外的发现写进 PR 的「后续」。
4. 提交前本机运行 ./gradlew build，必须通过。Issue 标 needs-device-test 或改动涉及设备行为的，先读 docs/systems/testing.md，再在设备上验证并记录结果。
5. 按 .github/pull_request_template.md 用 gh pr create 提交 PR，正文写 Closes #<N>，验收条件逐条对照，附测试证据。不要上传游戏或漫画截图，不要贴未脱敏的屏幕文本。
6. 不要合并 PR，不要推送 main。完成后告诉我 PR 链接。
```

审查后修改时：

```text
PR #<M> 收到审查意见，请用 `gh pr view <M> --comments` 和 `gh api repos/yizhiteamo/babel/pulls/<M>/comments` 读取全部意见。
在同一分支追加提交逐条处理阻塞项（不要 force-push），./gradlew build 通过后推送，并在 PR 下回复每条阻塞意见的处理方式。
```
