# 规格治理与 Canonical Source

Hearth 仍处于架构/规格阶段，允许领域模型快速演进，但同一个 invariant 只能有一个 owner 文档。README、
AGENTS、CLAUDE 和 `.claude/rules` 是入口/摘要，不得成为第二套详细规格。

## Owner map

| 主题 | Canonical source |
|---|---|
| 技术栈与禁止项 | `AGENTS.md` + `docs/00-stack-decision.md`（精确版本归 `docs/10-dependencies.md`） |
| 控制面/数据面、Gateway | `docs/01-architecture.md` |
| bounded context 边界 | `docs/02-modules.md` |
| Schema / migration | `docs/03-schema.md` |
| Platform MCP 工具 | `docs/04-platform-mcp.md` |
| REST / SSE | `docs/05-rest-api.md` |
| Internal Dispatch / A2A Adapter / loop defense | `docs/05-a2a-and-loops.md` |
| Memory semantics | `docs/06-memory.md`；实际 retention 值归 `docs/08-operations.md` |
| milestone scope / acceptance | `docs/07-roadmap.md` |
| 部署、认证、retention | `docs/08-operations.md` |
| Task/G4C+E/Evidence | `docs/09-g4c.md` |
| resilience / state transition | `docs/11-resilience.md` |
| Worker protocol | `docs/12-worker.md` |
| UI interaction | `docs/13-ui.md` |
| Pi Agent RPC Adapter 边界 | `docs/15-pi-agent-adr.md`；外部 runtime 版本归 `docs/10-dependencies.md` |

## 变更规则

1. 先改 owner 文档，再更新引用它的 API、UI、roadmap 和摘要。
2. 外部协议/依赖变化写 ADR 或在 owner 文档记录版本与验证日期；核心领域不直接依赖外部 wire type。
3. 规格变更必须说明旧 invariant、决策、受影响文档、migration/API compatibility 与验收办法。
4. 已发布 Flyway migration append-only；尚未落地的 SQL 草案可改，但首个共享环境上线后禁止回写。
5. `.claude/rules` 只负责“何时读哪个 spec”，不复制完整规则。

跨 context/invariant 的新变更使用 `changes/README.md` 定义的 proposal/spec-delta/tasks 流程；归档 change 只
解释历史，当前事实仍以 owner 文档为准。本轮不机械搬迁 `docs/` 到新的 `specs/` 路径，避免在没有实现收益
的情况下制造链接 churn；等规格边界稳定后再单独迁移。

## Drift check

提交文档变更前至少运行：

```bash
rg -n --glob '!16-spec-governance.md' \
  "hearth_dispatch_a2a|hearth_checkpoint_done|完整 V001|六个模块|M1.*MCP server|Pi.*不使用自身 Provider|Pi.*工具调用.*权限检查" \
  AGENTS.md CLAUDE.md README.md docs .claude
```

命中不一定全错，但必须逐条确认没有旧术语或 milestone 冲突。未来有代码后，把 canonical invariant 做成
可执行的 architecture/schema/API tests；提醒式 hook 不算约束。
