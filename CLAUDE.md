@AGENTS.md

---

## Claude Code 专属配置

### Rules（模块化约束）

- `.claude/rules/hearth-architecture.md` — 架构硬约束，防漂移
- `.claude/rules/hearth-modules.md` — 模块边界，防跨模块乱调用

全局 rules（security、testing、coding-style 等）已在 `~/.claude/rules/` 里，不在此重复。

### 长期记忆

每次会话结束前，检查以下内容是否需要更新：
- `docs/` 下的设计文档是否仍然准确
- `AGENTS.md` 里的"不做什么"是否需要补充新的决定
- 是否有新的踩坑值得记录进 `AGENTS.md` 的关键约束章节

有价值的决定 > 记录到文档 > 比记忆更可靠，因为文档可被 Codex 同步读到。

### MCP

见 `.mcp.json`（待 M1 实现 Hearth MCP server 后填入）。

### 开发时注意

- 每次修改代码后，运行相关测试验证没有漂移
- 提交前检查：是否违反了 AGENTS.md 里任何一条"不做什么"
- 架构疑问先查 `docs/` 对应文档，再问，不要重新讨论已定的决策
