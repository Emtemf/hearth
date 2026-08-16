# Tasks and acceptance

## 已完成

- [x] 定义 canonical source owner map 和变更记录流程。
- [x] 收敛 TaskRequest → TaskSpecVersion → PlanVersion → TaskExecution 生命周期。
- [x] 收敛 Schema、REST、Platform MCP、Internal Dispatch、Evidence、Worker 和 resilience 之间的引用关系。
- [x] 定义 WorkerClient、精确 process identity、command idempotency、event replay 和 cancellation fencing。
- [x] 定义 Session gateway/MCP capability、GatewayIngressAuthBinding 和协议专属 CredentialRewriter。
- [x] 定义 Pi RPC framing、semantic completion、resource isolation、provider routing、tool governance、resume 和版本兼容矩阵。
- [x] 修复 Platform MCP 和 G4C 文档中的多对象 JSON 示例。
- [x] 明确 MCP wire enum 与 core domain enum 的大小写映射。
- [x] 补充 automation/Schedule canonical owner 和 README 文档索引。
- [x] 移除未被项目规范引用的 `.codex/hooks.json`。

## 文档验收

- [ ] `git diff --check` 无输出。
- [ ] 所有本地 Markdown 相对链接可解析。
- [ ] JSON 示例在标记为 `json` 时均为单个有效 JSON 文档。
- [ ] drift check 未命中旧术语；命令自身排除在扫描范围外。
- [ ] credential-like scan 未发现真实密钥、token 或私钥。

## 实现前验收

- [ ] 将 canonical invariant 转换为 architecture/schema/API contract tests。
- [ ] 在全新 PostgreSQL 上按 V001+ 增量 migration 执行 migrate/upgrade/validate。
- [ ] M1 通过 Claude Code gateway、WorkerClient、两次 Invocation、Transcript、SSE 和 CSRF 验收。
- [ ] M2 通过 Worker、Task/Dispatch/Evidence 核心验收后，单独执行 Pi compatibility slice。
- [ ] Pi runtime 升级前重跑 framing、credential carrier、`agent_settled`、governance、resume 和 cancel contract tests。
