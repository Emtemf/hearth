# Tasks and acceptance

## M1.0 slice

- [ ] 创建最小 Maven reactor 与 `hearth-worker` 模块；不加入未来 M2/M3 模块。
- [ ] 先写测试，验证 Claude Code stdio command shape、NDJSON line parser、受控 child environment 和多次 stdin fixture protocol。
- [ ] 实现最小 harness，使 fixture test 通过。
- [ ] 添加 opt-in real CLI probe；没有显式 opt-in 时必须跳过，不调用 CLI。
- [ ] 记录 fixture 的 `STREAMING_STDIN` 结论和真实 CLI probe 的实际结论（通过、失败或 skipped）。
- [ ] 运行 module test、文档验证和 `git diff --check`。

## 验收

- [ ] 运行 `mvn -pl hearth-worker test`，所有 fixture tests 通过。
- [ ] 默认 test suite 不依赖真实 Claude Code、网络或 API key。
- [ ] 受控 child environment 不继承 `ANTHROPIC_API_KEY`、数据库、IM 或 admin secret 名称；仅允许 adapter allowlist 与 gateway capability 注入。
- [ ] 解析器拒绝非 JSON、非 object、过大行和没有 `type` 的事件，错误不包含 input 内容。
- [ ] fixture 证明 `result` 后可接受第二条 stdin，并输出两次完成事件。
- [ ] `python3 scripts/validate_docs.py`、`git diff --check` 通过。

## 后续 gate

- [ ] 在受控环境中以锁定 Claude Code version 运行 real CLI probe，记录 `ProcessReusePolicy` 和 stdout evidence 的 sha256。
- [ ] M1.1 才实现 Gateway fixture、credential rewriting、SSE streaming 和 Exchange recording。
- [ ] M1.2 才实现 `WorkerClient`、独立 daemon、受认证 transport、Process identity/generation、真实 child UID/permissions 验收。
