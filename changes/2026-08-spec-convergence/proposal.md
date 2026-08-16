# 规格收敛与 Pi Adapter 边界

## 状态

- 状态：已完成文档规格变更，运行时实现尚未开始
- 日期：2026-08-16
- 范围：Hearth architecture/specification baseline

## 问题与 Context

Hearth 的控制面、数据面、Task 版本模型、Worker 协议、凭证边界和 Pi Agent 适配器此前分散在多份设计文档中，存在未来实现时发生语义漂移的风险。尤其需要明确：

- TaskRequest、TaskSpecVersion、PlanVersion 与 TaskExecution 的生命周期边界；
- Schema、REST、Platform MCP 和 Internal Dispatch 的契约关系；
- Worker command/event fencing、幂等、重连和取消语义；
- Pi 作为独立 Worker RPC Adapter，而不是 Hearth Java 核心运行时；
- Pi provider transport、Gateway credential rewriting、资源隔离和工具治理边界。

SourceRef：本仓库 `docs/`、`AGENTS.md` 以及 Pi 上游调研基线 `docs/15-pi-agent-adr.md`。

## 决策

1. 以各 canonical owner 文档作为当前事实源，并用 `docs/16-spec-governance.md` 维护 owner map。
2. 核心控制面继续使用 Java 21/Spring MVC；Pi 仅作为 M2 后可选的 Worker 子进程 RPC Adapter。
3. Task 规格和计划使用不可变版本，运行状态与 Evidence 分离。
4. Worker command 使用稳定 `commandId`，事件使用 process generation 与 event sequence fencing。
5. Session capability 使用 opaque CSPRNG token、SHA-256 hash 和 audience 绑定；Gateway 认证后剥离内部 credential，再注入上游凭证。
6. Platform MCP 的 wire enum 使用 lowercase；Adapter 归一化为核心领域 uppercase enum，REST/数据库投影使用领域值。
7. Schedule/automation 语义由 `docs/14-automation.md` 单独拥有；自动化 Task 不能递归管理 Schedule。

## 非目标

- 本变更不实现 Hearth runtime、Web UI、Worker daemon 或数据库 migration。
- 本变更不引入 Pi SDK、Maven/npm runtime dependency 或新的 Pi 核心领域表。
- 本变更不承诺 Pi 的工具调用已经具备前置权限阻断；默认治理等级仍为 `OBSERVE_ONLY`。
