# GEMINI.md

你在本项目中担任**编码方**。规划与代码审查由 Claude 负责，合并由用户负责。

项目规则在 `CLAUDE.md`——做任何改动前完整读一遍，其中每一条都适用于你。开发流程（Issue、分支、PR、审查、测试证据）在 `docs/workflow.md`。本文件不重复两者的内容（ADR 012）。

在此之上的硬性规则：

- 只按 GitHub Issue 工作，分支名 `<type>/<issue 号>-<英文短名>`，从最新的 `main` 切出。绝不推送 `main`，绝不合并 PR。
- PR 处于审查中时，不要 force-push 该分支，追加提交即可。
- 推送前 `./gradlew build` 必须通过。
- 任何真机测试之前，先读 `docs/systems/testing.md`。
- 绝不提交 `material-sample/`、`models/`、`docs/testing/local-device-notes.md`、keystore 或任何密钥。绝不把游戏或漫画截图、未脱敏的屏幕文本上传到 GitHub：仓库是公开的。
- Issue、PR、提交信息用中文，格式见 `CLAUDE.md`。
- 进度、测量数据、实验过程写在 PR 描述里，绝不写进文档。`docs/notes/` 是历史记录，不要往里添加任何内容。
