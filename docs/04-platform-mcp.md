# Platform MCP Server：工具定义

Hearth 自己跑一个 MCP Server，注入每个 agent session 的 `.mcp.json`。
这是 agent 参与平台编排的唯一正式通路——所有 A2A 派发、Artifact 上传、记忆检索、
Checkpoint 上报、问题上报都走这里。

**为什么必须有这个**：没有 MCP Server，agent 只能靠输出文字让人解析，
Evidence 无法落地，G4C+E 框架退化成形式。

---

## 工具列表

### `hearth_dispatch_a2a`

派发一条 A2A 消息给另一个 agent。由 orchestrator 拦截，注入 TraceContext，
验证预算和环检测，再实际派发。agent 不能绕过此工具直接通信。

```json
{
  "name": "hearth_dispatch_a2a",
  "description": "向另一个 agent 派发任务或咨询。orchestrator 会自动注入 traceId、检查预算和环。",
  "inputSchema": {
    "type": "object",
    "required": ["commandId", "target", "kind", "content"],
    "properties": {
      "commandId": {
        "type": "string",
        "format": "uuid",
        "description": "调用方生成的稳定幂等键；网络重试必须复用"
      },
      "target": {
        "oneOf": [
          {"type":"object","required":["spawnRole"],"properties":{"spawnRole":{"type":"string"}}},
          {"type":"object","required":["existingSessionId"],"properties":{"existingSessionId":{"type":"string"}}}
        ],
        "description": "spawnRole 创建新 Session；existingSessionId 激活已有 Session，二者不可混用"
      },
      "kind": {
        "type": "string",
        "enum": ["request", "consult", "notify", "escalate"],
        "description": "request=完整委托可再派生; consult=询问不可再派生; notify=单向; escalate=上报Goal无法实现"
      },
      "content": {
        "type": "string",
        "description": "消息内容"
      },
      "evidenceArtifactIds": {
        "type": "array",
        "items": { "type": "string" },
        "description": "相关 Artifact ID 列表。kind=escalate 时必填且不能为空"
      },
      "budgetHint": {
        "type": "object",
        "description": "可选：建议给目标任务分配的预算。orchestrator 会验证是否超出父预算",
        "properties": {
          "tokens": { "type": "integer" },
          "wallMs": { "type": "integer" },
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

副作用结果统一区分：`COMMITTED`、`COMMITTED_WITH_WARNING`、`REJECTED_BEFORE_COMMIT`、
`UNKNOWN_COMMIT_STATE`。消息已持久化但实时广播失败时返回 `COMMITTED_WITH_WARNING`，重试同一
`commandId` 返回原 messageId，不重复投递；`UNKNOWN_COMMIT_STATE` 进入 Inbox，不允许 agent 自动重试。

**错误返回**（不会抛异常，以结构化错误返回让 agent 决定怎么处理）：
```json
{
  "error": "a2a.cycle_detected",
  "ancestorChain": ["session-A", "session-B", "session-A"]
}
{
  "error": "a2a.budget_exhausted",
  "remaining": { "tokens": 0, "wallMs": 12000, "usd": 0.0 }
}
{
  "error": "a2a.escalate_requires_evidence",
  "message": "kind=escalate 时 evidenceArtifactIds 不能为空"
}
```

---

### `hearth_save_artifact`

保存产出物到中心 Artifact 存储，返回可跨 session 引用的 artifactId。
这是 Evidence 的物质基础——没有 artifactId，断言不成立。

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
        "description": "内容本体（M1 只接受内容，不接受主机 path；< 1MB 直接内嵌，>= 1MB 走分块上传）"
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
  "url": "/api/artifacts/art-abc123",
  "sizeBytes": 4821
}
```

---

M1 不提供 path 参数，防止 agent 把 allowed workspace 之外的主机文件上传。未来增加 path 上传时，必须由
Worker 对 `session cwd + relative path` 做 real-path canonicalization、符号链接逃逸检查和文件大小上限；
分块上传使用 uploadId、chunk hash、总 sha256 和幂等 finalize。

---

### `hearth_checkpoint_done`

上报一个 Checkpoint 已完成，并提交 Evidence Artifact。
编排层收到后**独立验证** Artifact 内容，不相信 agent 的文字结论。

```json
{
  "name": "hearth_checkpoint_done",
  "description": "上报 checkpoint 完成。必须提供 Evidence Artifact ID，编排层会独立验证，不接受文字断言。",
  "inputSchema": {
    "type": "object",
    "required": ["checkpointId", "artifactId"],
    "properties": {
      "checkpointId": {
        "type": "string",
        "description": "Task.checkpoints 里定义的 checkpoint ID"
      },
      "artifactId": {
        "type": "string",
        "description": "证明 checkpoint 完成的 Artifact ID（如测试报告、代码 diff）"
      },
      "notes": {
        "type": "string",
        "description": "可选：补充说明（编排层验证失败时会用到）"
      }
    }
  }
}
```

**返回**：
```json
{ "status": "verifying" }
```
验证结果通过 A2A notify 异步推送，不同步等待（避免阻塞 agent）。

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

获取当前任务的 Goal、Context、Checkpoint 列表。agent 启动时应该主动调用，
确保理解任务目标，不靠 system prompt 里的文字记忆。

```json
{
  "name": "hearth_get_task_context",
  "description": "获取当前任务的完整 G4C+E 上下文（Goal、已知 Context、Checkpoint 列表）。",
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
  "goal": "POST /auth/login 返回 200，mvn test 退出码为 0，grep -r plaintext_password src/ 无输出",
  "context": {
    "known": [
      { "fact": "项目使用 PostgreSQL 16", "source": "file:pom.xml:42" }
    ],
    "gaps": ["JWT 过期时间尚未确定，需要向 architect 咨询"]
  },
  "checkpoints": [
    { "id": "cp-001", "after": "核心逻辑实现", "verify": "mvn test -pl hearth-gateway" }
  ],
  "budgetRemaining": { "tokens": 145000, "wallMs": 2700000, "usd": 1.24 }
}
```

---

## .mcp.json 注入格式

每个 session 启动时，session overlay 目录里写入：

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
3. 完成 checkpoint 时必须先 save_artifact，再用 artifactId 调用 hearth_checkpoint_done
4. 需要其他 agent 协作时通过 hearth_dispatch_a2a，不要在输出中直接 @mention
5. 发现 Goal 无法实现时立刻调用 hearth_dispatch_a2a(kind=escalate)，不要假装可以做到
6. 检索记忆时用 hearth_retrieve_memory，把返回的 memoryId 记录在 context.known 来源里
```
