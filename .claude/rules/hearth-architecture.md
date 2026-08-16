# Hearth 架构规则路由

开始相关改动前读取对应 canonical spec：

- 控制面 / 数据面、透明网关与 MCP milestone：`docs/01-architecture.md`
- 技术栈与禁止项：根目录 `AGENTS.md`、`docs/00-stack-decision.md`
- 精确依赖版本与构建模块：`docs/10-dependencies.md`
- Pi Agent 外部 runtime / RPC Adapter 决策：`docs/15-pi-agent-adr.md`
- 安全配置、认证、retention 与部署：`docs/08-operations.md`
- REST/SSE 契约：`docs/05-rest-api.md`

硬约束不在 `.claude/rules` 重复维护。发现冲突按 `docs/16-spec-governance.md` 处理，修改 canonical source 后再
同步 README/AGENTS 等摘要。
