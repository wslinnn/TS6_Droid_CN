# release-notes — 手写发行说明

发版前把本版本的中文发布说明写在 `vX.Y.Z-Han.md`（与 tag 严格同名）。

发布 workflow 会把该文件附在固定的安装说明之后一起写入 GitHub Release；
文件不存在时 Release 只包含默认安装说明，流程照常。

要点：

- 只写本版本的更新与修复内容，按「功能分组小标题 + 条目」组织
- 历史版本说明可在 Git 历史里追溯，文件随版本落库、过期不删
- 发布后如需替换为累计式完整说明，仍按既有流程走 GitHub API 更新 Release body
