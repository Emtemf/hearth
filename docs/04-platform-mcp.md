# Platform MCP Server：工具定义

Hearth 自己跑一个中心 Platform MCP Server，并为每个 agent session 生成独立的受保护 MCP overlay 配置。
这是 agent 参与平台编排的唯一正式通路——所有 Internal Dispatch、Artifact 上传、记忆检索、
Checkpoint 上报、问题上报都走这里。

**为什么必须有这个**：没有 MCP Server，agent 只能靠输出文字让人解析，
Evidence 无法落地，G4C+E 框架退化成形式。

---

## 工具列表

Platform MCP 从 M2 启用。M2 注册 `hearth_dispatch`、`hearth_save_artifact`、
`hearth_submit_claim`、`hearth_get_task_context`；M3 在 memory application port 可用后才注册
`hearth_retrieve_memory`。未到里程碑的工具不出现在 `tools/list`，不能注册一个永远返回 feature disabled 的
假工具。M1 不启动 MCP endpoint，也不签发 MCP audience token。

所有副作用工具的内部 `commandId` 由可信 MCP transport/Adapter 使用 authenticated session、Invocation 和
tool-call request identity 签发或确定性派生；不暴露给模型填写。transport retry 必须保留同一 request identity。

### `hearth_dispatch`

派发一条 Hearth Internal Dispatch 消息。由 orchestrator 注入 TraceContext，
验证预算和环检测，再实际派发。agent 不能绕过此工具直接通信。

```json
{
  "name": "hearth_dispatch",
  "description": "向另一个 agent 派发任务或咨询。orchestrator 会自动注入 traceId、检查预算和环。",
  "inputSchema": {
    "type": "object",
    "required": ["kind", "content"],
    "properties": {
      "target": {
        "oneOf": [
          {"type":"object","required":["spawnRole"],"properties":{"spawnRole":{"type":"string"}}},
          {"type":"object","required":["existingSessionId"],"properties":{"existingSessionId":{"type":"string"}}}
        ],
        "description": "request 必须用 spawnRole；consult/notify 用 existingSessionId；escalate 省略 target"
      },
      "kind": {
        "type": "string",
        "enum": ["request", "consult", "notify", "escalate"],
        "description": "request=创建 Child Task 后执行; consult=仅创建 Invocation 且不可派生; notify=单向; escalate=上报当前 Goal 受阻"
      },
      "content": {
        "type": "string",
        "description": "消息内容"
      },
      "evidenceClaimIds": {
        "type": "array",
        "items": { "type": "string" },
        "description": "相关 EvidenceClaim/失败 Verification ID。kind=escalate 时必填且不能为空"
      },
      "budgetHint": {
        "type": "object",
        "description": "可选：建议给目标任务分配的预算。orchestrator 会验证是否超出父预算",
        "properties": {
          "tokens": { "type": "integer" },
          "deadline": { "type": "string", "format": "date-time" },
          "usd": { "type": "number" }
        }
      }
    }
  }
}
```

**返回**：
```json
{
  "messageId": "msg-xxx",
  "commitState": "COMMITTED",
  "deliveryStatus": "dispatched",
  "traceContext": { "spanId": "...", "depth": 2, "budgetNodeId": "..." }
}
```

MCP 工具 schema 的枚举值使用 lowercase wire spelling（例如 `request`、`checkpoint`）。Adapter 入站时必须
将其显式归一化为核心领域的 uppercase enum（例如 `REQUEST`、`CHECKPOINT`）；REST 和数据库投影使用领域值，
不得让大小写差异由各 Controller 自行猜测。

副作用结果统一区分：`COMMITTED`、`COMMITTED_WITH_WARNING`、`REJECTED_BEFORE_COMMIT`、
`UNKNOWN_COMMIT_STATE`。消息已持久化但实时广播失败时返回 `COMMITTED_WITH_WARNING`，重试同一
内部 `commandId` 返回原 messageId，不重复投递；`UNKNOWN_COMMIT_STATE` 进入 Inbox，不允许 agent 自动重试。

**错误返回**（不会抛异常，以结构化错误返回让 agent 决定怎么处理）：

```json
{
  "error": "dispatch.target_kind_invalid",
  "message": "REQUEST requires spawnRole"
}
```

```json
{
  "error": "dispatch.budget_exhausted",
  "remaining": { "tokens": 0, "usd": 0.0, "deadline": "2026-08-13T18:00:00Z" }
}
```

```json
{
  "error": "dispatch.escalate_requires_evidence",
  "message": "kind=escalate 时 evidenceClaimIds 不能为空"
}
```

---

### `hearth_save_artifact`

保存产出物到中心 Artifact 存储，返回可跨 session 引用的 artifactId。
Artifact 是 Evidence 的材料载体，但 artifactId 本身不证明任何断言；证明关系由 Claim/Verification 建立。

```json
{
  "name": "hearth_save_artifact",
  "description": "保存产出物（代码变更、测试报告、文档等）并获得一个可被其他 agent 引用的 artifactId。",
  "inputSchema": {
    "type": "object",
    "required": ["type", "content"],
    "properties": {
      "type": {
        "type": "string",
        "enum": ["code_change", "document", "test_result", "screenshot", "plan", "review", "command_output"],
        "description": "产出物类型"
      },
      "content": {
        "type": "string",
        "description": "内容本体（M2 首期只接受内容，不接受主机 path；UTF-8 后最大 1 MiB）"
      },
      "title": {
        "type": "string",
        "description": "可选：人类可读的标题"
      },
      "metadata": {
        "type": "object",
        "description": "可选：附加元数据（如 filePath、exitCode、commandRun 等）"
      }
    }
  }
}
```

**返回**：
```json
{
  "artifactId": "art-abc123",
  "url": "/api/v1/artifacts/00000000-0000-0000-0000-000000000000",
  "sizeBytes": 4821
}
```

---

M2 首期不提供 path 参数，防止 agent 把 allowed workspace 之外的主机文件上传。内容小于 64 KiB 可存
Postgres，64 KiB–1 MiB 写本地 Artifact storage。未来增加 path/大文件上传时，必须由 Worker 的受限
`ArtifactUploadRelay` 对 `session cwd + relative path` 做 real-path canonicalization、符号链接逃逸检查和
文件大小上限；分块上传使用 uploadId、chunk hash、总 sha256 和幂等 finalize。

---

### `hearth_submit_claim`

为 Checkpoint/Goal/Operation 上报具体 Execution Claim，并关联 Artifact 材料。编排层按 PlanVersion 中的
verifier policy 调度 deterministic tool、独立 reviewer Session 或 human verifier。

```json
{
  "name": "hearth_submit_claim",
  "description": "提交一个可验证断言及其材料；Artifact 本身不代表断言已通过。",
  "inputSchema": {
    "type": "object",
    "required": ["subjectType", "subjectRef", "statement", "artifactIds", "sourceStateRef"],
    "properties": {
      "subjectType": {
        "type": "string",
        "enum": ["checkpoint", "goal", "artifact", "operation"]
      },
      "subjectRef": { "type": "string" },
      "statement": { "type": "string" },
      "artifactIds": {
        "type": "array",
        "minItems": 1,
        "items": { "type": "string" },
        "description": "支撑材料 Artifact ID；不自动等于验证通过"
      },
      "sourceStateRef": {
        "type": "object",
        "description": "workspaceRootId/relativeCwd、git tree 或 base+diff SHA、命令和工具版本"
      },
      "notes": {
        "type": "string",
        "description": "可选补充，不作为验证结论"
      }
    }
  }
}
```

**返回**：
```json
{ "claimId": "claim-uuid", "status": "PENDING_VERIFICATION" }
```
验证结果通过 Internal Dispatch notify 异步推送，不同步等待。语义 checkpoint 由 reviewer Session 产出
Review Artifact 后写 VerificationRecord，不能假装由 orchestrator deterministic verify。

---

### `hearth_retrieve_memory`

从 L2 记忆库检索和当前任务相关的高价值记忆。

```json
{
  "name": "hearth_retrieve_memory",
  "description": "检索与当前任务相关的历史决策、踩坑、偏好等高价值记忆。",
  "inputSchema": {
    "type": "object",
    "required": ["query"],
    "properties": {
      "query": {
        "type": "string",
        "description": "自然语言检索词"
      },
      "kinds": {
        "type": "array",
        "items": { "type": "string", "enum": ["decision", "constraint", "preference", "pitfall", "fact", "correction"] },
        "description": "可选：过滤记忆类型"
      },
      "limit": {
        "type": "integer",
        "default": 5,
        "maximum": 20,
        "description": "返回条数上限"
      }
    }
  }
}
```

**返回**：
```json
{
  "memories": [
    {
      "id": "mem-xyz",
      "kind": "pitfall",
      "content": "Spring AI 2.0 的 pgvector artifact 名称是 spring-ai-starter-vector-store-pgvector，旧名称会报错",
      "score": 0.89,
      "source": "memory:mem-xyz"
    }
  ]
}
```

**重要**：返回的记忆必须在 Context 的 `known` 里标注来源为 `memory:{id}`，不能直接当成事实使用。

---

### `hearth_get_task_context`

获取当前任务固定引用的 `TaskSpecVersion`、`PlanVersion`、预算和截止时间。agent 启动时应该主动调用，
确保理解当前冻结的任务目标和计划，不靠 system prompt 里的文字记忆。

```json
{
  "name": "hearth_get_task_context",
  "description": "获取当前执行固定引用的 TaskSpecVersion、PlanVersion 与预算/截止时间。",
  "inputSchema": {
    "type": "object",
    "properties": {}
  }
}
```

**返回**：
```json
{
  "taskId": "task-001",
  "specVersionId": "spec-uuid",
  "planVersionId": "plan-uuid",
  "goal": "POST /auth/login 返回 200，mvn test 退出码为 0，grep -r plaintext_password src/ 无输出",
  "context": {
    "known": [
      { "id": "ctx-1", "kind": "FACT", "statement": "项目使用 PostgreSQL 16", "sourceRefs": ["file:pom.xml:42"] }
    ],
    "gaps": ["JWT 过期时间尚未确定，需要向 architect 咨询"]
  },
  "checkpoints": [
    { "id": "cp-001", "after": "核心逻辑实现", "verify": "mvn test -pl hearth-gateway" }
  ],
  "budgetRemaining": { "tokens": 145000, "usd": 1.24 },
  "deadline": "2026-08-13T18:00:00Z"
}
```

---

## Session 专属 MCP 配置注入格式

M2 每个 Session 启动时，在 session overlay 目录写入 `hearth-mcp.json`，并用
`--strict-mcp-config --mcp-config {overlay}/hearth-mcp.json` 启动 Claude Code。禁止写项目根 `.mcp.json`，
避免污染用户仓库、并发 Session 互相覆盖或凭证被 git 收集。

```json
{
  "mcpServers": {
    "hearth": {
      "url": "http://127.0.0.1:4517/mcp/s/{sessionId}",
      "headers": {
        "Authorization": "Bearer {mcpCapabilityToken}"
      }
    }
  }
}
```

URL 包含 sessionId，服务端据此确定 TraceContext、预算账本、任务上下文。
`mcpCapabilityToken` 是创建 session 时签发的独立 opaque token，只能访问该 session 的 MCP audience；
服务端只存 SHA-256 hash。它不得与 gateway token 复用，也不得写入日志或 Artifact。

---

## 工具使用规范（写进 agent 的 system prompt）

```
## Hearth Platform Tools

你有以下平台工具，使用规范：

1. 每次启动时调用 hearth_get_task_context 获取完整任务目标
2. 产出任何具体成果后立刻调用 hearth_save_artifact，不要等到最后再保存
3. 声称 checkpoint/Goal 完成时，先保存 Artifact，再用 hearth_submit_claim 提交具体断言和 sourceStateRef
4. 需要其他 agent 协作时通过 hearth_dispatch；request 会创建 Child Task，consult 只创建 Invocation
5. 发现 Goal 无法实现时先提交可核验的失败 Claim，再调用 hearth_dispatch(kind=escalate)
6. 检索记忆时用 hearth_retrieve_memory，把返回的 memoryId 记录在 context.known 来源里
```
