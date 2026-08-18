# M1.0 Claude Code 接入 spike

## 状态

- 状态：已接受，spike 实现中
- 日期：2026-08-17
- 范围：M1.0 接入 spike；仅验证 CLI stdio contract，不演化为生产 Controller

## 问题与 Context

M1 需要通过独立 Worker daemon 启动 Claude Code，并确认锁定 CLI 版本下的 `stream-json` 输入/输出、真实流式结果边界和同一进程是否能在 `result` 后接收第二次 stdin。当前仓库尚无运行时代码，因此第一片只建立可重复的 subprocess fixture harness，不接入 Spring Controller、Postgres、Gateway 或 Web UI。

SourceRef：`docs/07-roadmap.md`、`docs/00-stack-decision.md`、`docs/01-architecture.md`、`docs/12-worker.md`。

## 决策

1. 以受控 fake CLI 作为默认测试 fixture，避免单元测试依赖用户本机 Claude 登录、网络或 provider secret。
2. fixture 使用与 Claude Code 相同的 `-p --output-format stream-json --input-format stream-json --verbose` 参数形状，stdin/stdout 均为 NDJSON。
3. contract test 固定验证：首个 `system/init`、一个 assistant/tool/result 流、`result` 事件后第二条 user 输入、stdout 逐行解析和 child environment 最小化。
4. 将复用结果显式记录为 `STREAMING_STDIN` 或 `RESUME_PER_INVOCATION`；在真实 CLI 未执行时不宣称真实 CLI 已通过。
5. 真实 CLI smoke test 作为 opt-in 命令运行，缺少凭证或登录时明确报告 skipped，不把凭证写入日志或 fixture。

## 非目标

- 不实现 `WorkerClient`、`LocalWorkerClient`、`hearth-worker` daemon 或生产 `ProcessBuilder` 封装。
- 不实现 Gateway、Anthropic credential rewriting、Postgres、Flyway、REST、SSE 或 UI。
- 不把 fake fixture 结果当作真实 Claude Code 版本兼容证明。
