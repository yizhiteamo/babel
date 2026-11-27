# ADR 012 — 多 agent 协作，经 Pull Request 进入 main

## 状态
已接受。取代 ADR 007；修订 ADR 002、ADR 006。

## 决定
工作由三方分担：

- **Gemini**（在 Antigravity 中）编码，在本机和真机上测试，提交 Pull Request。
- **Claude** 规划（把验收条件拆成 GitHub Issue）并审查 Pull Request。
- **用户** 在两者之间传话，做产品决定，并合并。

每个改动都经过一个 CI 通过、经过审查的 Pull Request 进入 `main`，并以 squash 方式合并，使一个 Issue 对应一个提交。`main` 受分支保护，管理员也不例外。发版仍然只推 tag（`docs/systems/release.md`）。

**工作记录在 GitHub 上，不写进文档。** 任务、进度、缺陷、等待用户决定的问题都是 Issue，按 Milestone 分组；每个进行中的版本有一个置顶的跟踪 Issue，记录验收状态。一个改动背后的测量写在它的 PR 里。`docs/milestones/` 停用：其中的文件移到 `docs/notes/` 作为不再维护的历史记录，open-items 索引变成了 Issue。这修订了 ADR 002 和 ADR 006（二者曾把详细进度放在 `docs/milestones/`）。文档只保留长期成立的内容：范围与验收条件（`docs/features/`）、机制与理由（`docs/systems/`、ADR）、版本级状态（`docs/roadmap.md`）。

`CLAUDE.md` 仍是项目规则的唯一来源。`GEMINI.md` 与 `AGENTS.md` 只是指向它的指针，外加少量仅针对编码方的规则，从不复制它的内容。流程本身在 `docs/workflow.md`。

## 理由
一个 agent 直接提交到 `main`，既没有审查环节，也没有记录说明一个改动为什么被接受。把写改动的人和检查改动的人分开，能发现写的人自己看不到的问题，PR 也让证据和改动放在一起。

里程碑文件已经变成了实验记录本——仅 `v2.md` 就有 3480 行——当前事实和后文推翻的结论混在一起，而每个任务都得读它。Issue 做完就关闭，所以还开着的，正好就是剩下的工作。

ADR 007 删除 `AGENTS.md`，是因为两份完整副本会逐渐不一致。指针文件保留了这一点：每条规则仍然只有一份。

## 后果
- 三方共用一个 GitHub 账号，而 GitHub 不允许一个账号批准自己的 Pull Request，所以审查以评论形式给出，第一行写明结论。分支保护要求 CI 通过，不要求审批人数。
- CI 没有设备，也没有本地素材（`material-sample/`），所以真机证据必须写进 PR，并在审查中核对；涉及识别规则的 PR，审查方在本机设备上自行复跑。
- 仓库是公开的：PR 中不放游戏或漫画截图，不贴未脱敏的屏幕文本。
- 文档统一使用中文；代码、标识符与代码注释保持英文。
