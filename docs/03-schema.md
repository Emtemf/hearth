# 数据库 Schema

Flyway 管理，文件放 `hearth-core/src/main/resources/db/migration/`。
命名规范：`V{version}__{description}.sql`，version 三位补零（V001、V002…）。

---

## ER 概览

```
workspace ──< workspace_root
          ──< provider_route
          ──< task ──< session ──< session_process
                             ├────< invocation ──< exchange
                             └────< worker_event_receipt
                  ──< a2a_message
                  ──< budget_node ──< budget_node（自引用树）
                  ──< checkpoint

session ──< invocation ──< exchange
        ──< artifact ──< artifact_reference
        ──< session_credential
worker ──< worker_credential
       ──< session

task ──< cancellation_outbox
inbox_item ──> task / session（外键可空）
memory_card ──> source_sessions（JSONB 数组）
```

---

## V001__core_schema.sql — 完整核心结构

V001 一次创建 M1/M2 共用的完整关系结构。M1 只使用 `workspace`、`workspace_root`、`provider_route`、Profile、Worker、
`session`、`invocation`、`worker_event_receipt`、`session_credential`、`exchange`、`artifact` 和
`domain_event`；Task/A2A 表虽然存在，
但直到 M2 才有业务流量。**里程碑通过功能开关和应用入口控制，不通过缺表控制。**

这样所有外键在一次 migration 内均有合法目标，空库执行 V001 不依赖未来 migration。
创建顺序以 SQL 文件中的外键依赖为准；循环引用在双方表创建后用 `ALTER TABLE` 补上。

### workspace

```sql
CREATE TABLE workspace (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name        TEXT NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
```

M1/M2 单 workspace，表存在但只有一行。开源后扩展多租户。

### workspace_root / provider_route（受信配置引用）

```sql
CREATE TABLE workspace_root (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    workspace_id    UUID NOT NULL REFERENCES workspace(id),
    name            TEXT NOT NULL,
    canonical_path  TEXT NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (workspace_id, name),
    UNIQUE (workspace_id, canonical_path)
);

CREATE TABLE provider_route (
    id                    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    workspace_id          UUID NOT NULL REFERENCES workspace(id),
    name                  TEXT NOT NULL,
    wire_protocol         TEXT NOT NULL,
    upstream_base_url     TEXT NOT NULL,
    allowed_models        JSONB NOT NULL DEFAULT '[]',
    credential_ref        TEXT NOT NULL, -- 环境变量/secret manager 引用，不是 secret 内容
    enabled               BOOLEAN NOT NULL DEFAULT true,
    version               BIGINT NOT NULL DEFAULT 0,
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (workspace_id, name)
);
```

Session 创建 API 只接受这两张表的 ID 和相对路径。服务端解析 route 后固定目标 host、wire protocol、
模型白名单和 credential reference；解析 workspace root 后 canonicalize 路径并拒绝 `..`、符号链接逃逸
和根目录之外路径。M1 只做同 wire protocol 模型路由，不承担 Anthropic/OpenAI/Gemini 之间的协议翻译。

---

### agent_profile / agent_profile_version

```sql
CREATE TABLE agent_profile (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    workspace_id UUID NOT NULL REFERENCES workspace(id),
    role        TEXT NOT NULL,                  -- architect / coder / reviewer / assistant
    current_version_id UUID,                   -- 指向最新版本，启动后更新
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (workspace_id, role)
);

CREATE TABLE agent_profile_version (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    profile_id      UUID NOT NULL REFERENCES agent_profile(id),
    version         INTEGER NOT NULL,
    system_prompt   TEXT NOT NULL,
    tool_whitelist  JSONB NOT NULL DEFAULT '[]',   -- ["read_file","write_file",...]
    constraints     JSONB NOT NULL DEFAULT '[]',   -- [{rule, disposition}]
    preferred_model TEXT NOT NULL,
    memory_scope    JSONB NOT NULL DEFAULT '{}',   -- {workspace, agentRoles, tags}
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (profile_id, version),
    UNIQUE (id, profile_id)
);
```

---

### task

```sql
CREATE TABLE task (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    workspace_id    UUID NOT NULL REFERENCES workspace(id),
    parent_task_id  UUID REFERENCES task(id),
    title           TEXT NOT NULL,
    brief           TEXT NOT NULL,
    -- G4C+E
    goal            TEXT NOT NULL,              -- 可验证的成功标准
    context         JSONB NOT NULL DEFAULT '{"known":[],"gaps":[]}',
    choice          JSONB NOT NULL DEFAULT '{"decision":null,"contextSources":[]}',
    checkpoints     JSONB NOT NULL DEFAULT '[]',
    correction      JSONB NOT NULL DEFAULT '{"onCheckpointFail":"escalate_to_human","onGoalBlocked":"escalate_to_human"}',
    evidence_policy JSONB NOT NULL DEFAULT '{"required":true,"acceptedTypes":[]}',
    -- 触发
    trigger_kind    TEXT NOT NULL,              -- manual / cron / webhook / inbound_message
    trigger_config  JSONB NOT NULL DEFAULT '{}',
    -- 生命周期
    status          TEXT NOT NULL DEFAULT 'PENDING',
    version         BIGINT NOT NULL DEFAULT 0,
    cancellation_version BIGINT NOT NULL DEFAULT 0, -- 每次用户取消原子 +1
    persistence     TEXT NOT NULL DEFAULT 'ephemeral',  -- ephemeral / durable
    root_budget_id  UUID,                       -- 指向根 budget_node
    target_agent_role TEXT,
    trace_id        UUID NOT NULL DEFAULT gen_random_uuid(),
    -- 时间
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    ended_at        TIMESTAMPTZ,
    UNIQUE (id, workspace_id)
);

CREATE INDEX idx_task_workspace_status ON task(workspace_id, status);
CREATE INDEX idx_task_parent_id ON task(parent_task_id);
CREATE INDEX idx_task_trace_id ON task(trace_id);
ALTER TABLE task ADD CONSTRAINT fk_task_parent_same_workspace
    FOREIGN KEY (parent_task_id, workspace_id) REFERENCES task(id, workspace_id);
```

---

### budget_node

```sql
CREATE TABLE budget_node (
    id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    task_id                 UUID NOT NULL REFERENCES task(id),
    parent_id               UUID REFERENCES budget_node(id),   -- NULL = 根节点
    -- 预算
    granted_tokens          BIGINT NOT NULL DEFAULT 0,
    granted_wall_ms         BIGINT NOT NULL DEFAULT 0,
    granted_usd             NUMERIC(12,6) NOT NULL DEFAULT 0,
    -- 消耗
    spent_tokens            BIGINT NOT NULL DEFAULT 0,
    spent_wall_ms           BIGINT NOT NULL DEFAULT 0,
    spent_usd               NUMERIC(12,6) NOT NULL DEFAULT 0,
    -- 划拨给子节点的总量
    allocated_tokens        BIGINT NOT NULL DEFAULT 0,
    allocated_wall_ms       BIGINT NOT NULL DEFAULT 0,
    allocated_usd           NUMERIC(12,6) NOT NULL DEFAULT 0,

    created_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (id, task_id)
);

ALTER TABLE task
    ADD CONSTRAINT fk_task_root_budget_same_task
    FOREIGN KEY (root_budget_id, id) REFERENCES budget_node(id, task_id);
```

---

### worker

```sql
CREATE TABLE worker (
    id                  TEXT PRIMARY KEY,                 -- 稳定的本机生成 ID，不使用 hostname
    display_name        TEXT NOT NULL,
    hostname            TEXT NOT NULL,
    version             TEXT NOT NULL,
    capabilities        JSONB NOT NULL DEFAULT '[]',
    status              TEXT NOT NULL DEFAULT 'OFFLINE', -- ONLINE/OFFLINE/DRAINING
    connection_id       UUID,                             -- 当前 WebSocket 连接代次
    last_heartbeat_at   TIMESTAMPTZ,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_worker_status ON worker(status);
```

`connection_id` 每次 Worker 重连时更换，旧连接的迟到消息因代次不匹配而被拒绝。

---

### worker_credential（Worker→中心长连接凭证）

```sql
CREATE TABLE worker_credential (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    worker_id       TEXT NOT NULL REFERENCES worker(id) ON DELETE CASCADE,
    token_hash      BYTEA NOT NULL UNIQUE,             -- SHA-256(raw 256-bit token)
    expires_at      TIMESTAMPTZ NOT NULL,
    revoked_at      TIMESTAMPTZ,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_used_at    TIMESTAMPTZ
);

CREATE INDEX idx_worker_credential_worker
    ON worker_credential(worker_id)
    WHERE revoked_at IS NULL;
```

Worker 安装/配对时签发长连接凭证，明文只显示一次；它只能认证 Worker WebSocket，不能访问
session gateway 或 MCP。轮换时先增加新凭证，Worker 重连成功后撤销旧凭证，避免停机窗口。

---

### session

```sql
CREATE TABLE session (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    task_id             UUID REFERENCES task(id),            -- NULL in M1 (standalone sessions); NOT NULL enforced in M2
    workspace_id        UUID NOT NULL REFERENCES workspace(id),
    agent_profile_id    UUID NOT NULL REFERENCES agent_profile(id),
    profile_version_id  UUID NOT NULL,
    agent_role          TEXT NOT NULL,
    -- 观测
    observability_level  TEXT NOT NULL,   -- full / sidecar / none
    observability_reason TEXT NOT NULL,   -- proxied / subscription_auth / ...
    -- 模型路由（受信配置引用；快照字段用于历史解释）
    provider_route_id   UUID NOT NULL REFERENCES provider_route(id),
    requested_model     TEXT,
    effective_model     TEXT NOT NULL,
    upstream_base_url   TEXT NOT NULL,
    wire_protocol       TEXT NOT NULL,    -- anthropic / openai-responses / gemini / ...
    -- Worker
    worker_id           TEXT REFERENCES worker(id),
    -- 生命周期
    status              TEXT NOT NULL DEFAULT 'PENDING',
    version             BIGINT NOT NULL DEFAULT 0, -- 聚合状态 CAS
    fencing_generation  BIGINT NOT NULL DEFAULT 0, -- replacement/restart increments this value
    trace_id            UUID NOT NULL,
    span_id             UUID NOT NULL DEFAULT gen_random_uuid(),
    parent_span_id      UUID,
    depth               INTEGER NOT NULL DEFAULT 0,
    budget_node_id      UUID REFERENCES budget_node(id),
    resume_descriptor   JSONB,            -- adapter/version/CLI version/externalSessionId/carrier
    workspace_root_id   UUID NOT NULL REFERENCES workspace_root(id),
    relative_cwd        TEXT NOT NULL,
    -- 时间
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    ended_at            TIMESTAMPTZ,
    FOREIGN KEY (profile_version_id, agent_profile_id)
        REFERENCES agent_profile_version(id, profile_id)
);

CREATE INDEX idx_session_task_id ON session(task_id);
CREATE INDEX idx_session_trace_id ON session(trace_id);
CREATE INDEX idx_session_status ON session(status) WHERE status NOT IN ('COMPLETED','FAILED','CANCELLED','TIMED_OUT','CRASHED','TERMINATED');
```

`task_id` 在 M1 standalone session 中允许为 `NULL`，M2 编排 session 必须由应用层保证非空；
不在数据库层全局改成 `NOT NULL`，否则会破坏长期保留的手动 session 能力。
`resume_descriptor` 是 adapter 专属的带版本结构，禁止把未知裸字符串直接传给 CLI。恢复时重新解析
`provider_route.credential_ref` 当前指向的 secret；不在 Session 中冻结或持久化 provider secret。

### session_process（具体 CLI 进程代次）

```sql
CREATE TABLE session_process (
    session_id          UUID NOT NULL REFERENCES session(id),
    process_generation  BIGINT NOT NULL,
    worker_id           TEXT NOT NULL REFERENCES worker(id),
    launch_id           UUID NOT NULL UNIQUE,
    status              TEXT NOT NULL DEFAULT 'LAUNCHING',
                        -- LAUNCHING/ALIVE/EXITED/TERMINATING/TERMINATED/LOST
    pid_hint            BIGINT, -- 仅诊断；远程命令不以 PID 作为身份
    exit_code           INTEGER,
    started_at          TIMESTAMPTZ,
    ended_at            TIMESTAMPTZ,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (session_id, process_generation),
    UNIQUE (session_id, process_generation, launch_id)
);
```

`session.fencing_generation` 指向当前代次；历史代次不覆盖删除。所有 LAUNCH/CANCEL/event 都携带
`(sessionId, processGeneration, launchId)`，Worker 只对完全匹配的进程执行命令，PID 不能跨重启作为身份。

### invocation（一次 agent activation）

```sql
CREATE TABLE invocation (
    id                    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    session_id            UUID NOT NULL REFERENCES session(id),
    process_generation    BIGINT NOT NULL,
    command_id            UUID NOT NULL,
    attempt               INTEGER NOT NULL DEFAULT 1,
    status                TEXT NOT NULL DEFAULT 'PENDING',
                          -- PENDING/RUNNING/SEMANTIC_COMPLETED/FAILED/TIMED_OUT/CANCELLING/CANCELLED
    version               BIGINT NOT NULL DEFAULT 0,
    terminal_reason       TEXT,
    started_at            TIMESTAMPTZ,
    semantic_completed_at TIMESTAMPTZ,
    ended_at              TIMESTAMPTZ,
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (session_id, command_id, attempt),
    FOREIGN KEY (session_id, process_generation)
        REFERENCES session_process(session_id, process_generation)
);

CREATE INDEX idx_invocation_session ON invocation(session_id, created_at);
CREATE INDEX idx_invocation_non_terminal ON invocation(status)
    WHERE status NOT IN ('SEMANTIC_COMPLETED','FAILED','TIMED_OUT','CANCELLED');
```

Session 是可跨多轮、可换进程代次的逻辑会话；Invocation 才是一次 user turn 或编排激活。
`semantic_completed`、`stream_eof`、`process_exited` 和 `transport_disconnected` 分别记录，禁止因为 CLI
stdout 仍打开而把已经完成的 Invocation 误判为超时，也禁止因为 transport 断开就假定进程死亡。

### worker_event_receipt（控制面事件重放水位）

```sql
CREATE TABLE worker_event_receipt (
    session_id         UUID NOT NULL REFERENCES session(id),
    process_generation BIGINT NOT NULL,
    last_event_seq     BIGINT NOT NULL DEFAULT 0,
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (session_id, process_generation)
);
```

Worker 对每代进程生成从 1 单调递增的 `eventSeq`，本地持久化未确认事件；中心按此表的水位去重、确认
并在断线后请求重放。业务可见顺序仍使用全局 `domain_event.event_id`，两种序号用途不同。

---

### session_credential（内部 capability token）

```sql
CREATE TABLE session_credential (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    session_id      UUID NOT NULL REFERENCES session(id) ON DELETE CASCADE,
    worker_id       TEXT REFERENCES worker(id),
    audience        TEXT NOT NULL,                  -- gateway / mcp
    token_hash      BYTEA NOT NULL,                 -- SHA-256(raw token)，不存明文
    expires_at      TIMESTAMPTZ NOT NULL,
    revoked_at      TIMESTAMPTZ,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_used_at    TIMESTAMPTZ,
    UNIQUE (token_hash),
    UNIQUE (session_id, audience, token_hash)
);

CREATE INDEX idx_session_credential_lookup
    ON session_credential(token_hash)
    WHERE revoked_at IS NULL;
CREATE INDEX idx_session_credential_session
    ON session_credential(session_id, audience);
```

创建 session 时分别签发 gateway 与 MCP 两枚独立的 256-bit CSPRNG opaque token；
只把明文返回给启动方一次。认证时对收到的 token 做 SHA-256 后等值查询，并同时验证
`session_id`、`worker_id`、`audience`、`expires_at`、`revoked_at`。token 不携带可解析元数据，
也不复用上游 provider credential。

---

### exchange（网关捕获的每次模型调用）

```sql
CREATE TABLE exchange (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    session_id      UUID NOT NULL REFERENCES session(id),
    invocation_id   UUID NOT NULL REFERENCES invocation(id),
    trace_id        UUID NOT NULL,
    -- 请求
    system_prompt   TEXT,               -- full 模式有值，sidecar/none 为 null
    message_count   INTEGER,
    requested_model TEXT,
    effective_model TEXT NOT NULL,
    rewritten       BOOLEAN NOT NULL DEFAULT false,
    request_bytes   INTEGER,
    body_ref        TEXT,               -- 对象存储路径（完整请求体）
    -- 响应
    status_code     INTEGER,
    input_tokens    BIGINT,
    output_tokens   BIGINT,
    cache_read_tokens BIGINT,
    cache_write_tokens BIGINT,
    usd             NUMERIC(12,6),
    latency_ms      INTEGER,
    streamed        BOOLEAN,
    stop_reason     TEXT,
    response_body_ref TEXT,
    -- 录制状态（独立于 observability_level：level 是接入机制，status 是实际结果）
    recording_status    TEXT NOT NULL DEFAULT 'complete',   -- complete/partial/failed
    recording_gap_reason TEXT,   -- queue_overflow/storage_failure/parse_failure（partial/failed 时必填）
    -- 时间
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_exchange_session_id ON exchange(session_id);
CREATE INDEX idx_exchange_invocation_id ON exchange(invocation_id);
CREATE INDEX idx_exchange_trace_id ON exchange(trace_id);
```

请求头解析完成后先插入 exchange；流结束、客户端断开或转发失败时更新响应、token、延迟和
`recording_status`，因此 exchange 是受控更新的调用聚合，不是 immutable event。
`recording_status='complete'` 时 `recording_gap_reason` 必须为 `NULL`；`partial/failed` 时必须有原因。

---

### artifact

```sql
CREATE TABLE artifact (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    session_id      UUID NOT NULL REFERENCES session(id),
    task_id         UUID REFERENCES task(id),               -- NULL in M1; NOT NULL enforced in M2
    type            TEXT NOT NULL,      -- code_change / document / test_result / ...
    status          TEXT NOT NULL DEFAULT 'PENDING_UPLOAD',
                                    -- PENDING_UPLOAD/AVAILABLE/UPLOAD_FAILED/DELETED
    title           TEXT,
    content         TEXT,               -- 小文件直接存（< 64KB）
    storage_ref     TEXT,               -- 大文件存对象存储，路径在这里
    size_bytes      BIGINT,
    sha256          TEXT,               -- AVAILABLE 后必填；删除后保留用于核验 tombstone
    metadata        JSONB NOT NULL DEFAULT '{}',
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at      TIMESTAMPTZ
);

CREATE INDEX idx_artifact_session_id ON artifact(session_id);
CREATE INDEX idx_artifact_task_id ON artifact(task_id);
CREATE INDEX idx_artifact_cleanup ON artifact(status, created_at);
```

`PENDING_UPLOAD → AVAILABLE | UPLOAD_FAILED`；失败可重试并回到 `PENDING_UPLOAD`。
清理只允许 `AVAILABLE → DELETED`，同时清空 `content/storage_ref`，保留同一行的
`id/sha256/size_bytes/deleted_at/metadata` 作为 tombstone。删除前必须确认 `artifact_reference`
计数为 0。

### artifact_reference

```sql
CREATE TABLE artifact_reference (
    artifact_id        UUID NOT NULL REFERENCES artifact(id),
    referrer_type      TEXT NOT NULL, -- checkpoint/inbox/operation_log/memory_card/domain_event
    referrer_id        TEXT NOT NULL,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (artifact_id, referrer_type, referrer_id)
);

CREATE INDEX idx_artifact_reference_referrer
    ON artifact_reference(referrer_type, referrer_id);
```

引用创建/删除与引用方业务写入在同一事务中完成；清理器用行锁再次检查零引用，避免检查后新增引用的竞态。

---

### a2a_message

```sql
CREATE TABLE a2a_message (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    idempotency_key     TEXT NOT NULL UNIQUE,        -- 幂等键，格式 {traceId}:{spanId}
    task_id             UUID REFERENCES task(id),    -- NULL in M1
    from_session_id     UUID NOT NULL REFERENCES session(id),
    to_agent_role       TEXT NOT NULL,
    to_session_id       UUID REFERENCES session(id), -- 初始可空，目标 Session 创建后回填
    kind                TEXT NOT NULL,  -- request / consult / notify / escalate
    content             TEXT NOT NULL,
    artifact_ids        JSONB NOT NULL DEFAULT '[]',
    downgraded_from     TEXT,           -- 被降级前的原始 kind
    -- trace（签发后不可变快照，不只存在内存）
    trace_id            UUID NOT NULL,
    span_id             UUID NOT NULL,
    parent_span_id      UUID,
    depth               INTEGER NOT NULL,
    ancestor_session_ids JSONB NOT NULL DEFAULT '[]',  -- 持久化祖先链，用于环检测审计
    -- 状态与重试
    status              TEXT NOT NULL DEFAULT 'DISPATCHED',  -- DISPATCHED/DELIVERED/ACKNOWLEDGED/FAILED/DEAD_LETTER
    attempt             INTEGER NOT NULL DEFAULT 1,
    next_retry_at       TIMESTAMPTZ,
    -- 时间
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    delivered_at        TIMESTAMPTZ,
    acknowledged_at     TIMESTAMPTZ
);

CREATE INDEX idx_a2a_trace_id ON a2a_message(trace_id);
CREATE INDEX idx_a2a_from_session ON a2a_message(from_session_id);
```

---

### inbox_item

```sql
CREATE TABLE inbox_item (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    workspace_id    UUID NOT NULL REFERENCES workspace(id),
    kind            TEXT NOT NULL,      -- approval / budget_exceeded / loop_detected / ...
    title           TEXT NOT NULL,
    body            TEXT NOT NULL,
    -- 关联（可空）
    task_id         UUID REFERENCES task(id),
    session_id      UUID REFERENCES session(id),
    trace_id        UUID,
    -- 行动选项
    actions         JSONB NOT NULL DEFAULT '[]',    -- [{id, label}]
    -- 状态
    status          TEXT NOT NULL DEFAULT 'OPEN',   -- OPEN / ESCALATED / RESOLVED / CANCELLED
    version         BIGINT NOT NULL DEFAULT 0,
    resolved_action TEXT,
    -- 时间
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    resolved_at     TIMESTAMPTZ,
    escalated_at    TIMESTAMPTZ
);

CREATE INDEX idx_inbox_workspace_status ON inbox_item(workspace_id, status);
```

---

### operation_log（undo 清单基础）

```sql
CREATE TABLE operation_log (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    session_id      UUID NOT NULL REFERENCES session(id),
    invocation_id   UUID NOT NULL REFERENCES invocation(id),
    task_id         UUID REFERENCES task(id),
    command_id      UUID NOT NULL,
    tool_name       TEXT NOT NULL,
    tool_input      JSONB NOT NULL,
    status          TEXT NOT NULL DEFAULT 'CLAIMED',
                    -- CLAIMED/COMMITTED/COMMITTED_WITH_WARNING/REJECTED_BEFORE_COMMIT/UNKNOWN_COMMIT_STATE
    tool_result     JSONB,
    external_operation_id TEXT,
    -- 文件操作快照（写操作前的内容）
    snapshot_ref    TEXT,               -- 对象存储路径，内容是操作前文件内容
    file_path       TEXT,
    -- 时间
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    committed_at    TIMESTAMPTZ,
    UNIQUE (session_id, command_id)
);

CREATE INDEX idx_oplog_session_id ON operation_log(session_id);
CREATE INDEX idx_oplog_invocation_id ON operation_log(invocation_id);
CREATE INDEX idx_oplog_task_id ON operation_log(task_id);
```

有副作用的工具必须在执行前用稳定 `command_id` claim；重试复用同一 ID，并返回已持久化结果而不是
再次执行。提交成功但响应/广播失败时使用 `COMMITTED_WITH_WARNING`，不能返回会诱导 agent 重试的
普通 error；无法判断是否提交时用 `UNKNOWN_COMMIT_STATE` 并进入 Inbox，禁止自动重试。

---

### cancellation_outbox（高优先级、可恢复取消命令）

```sql
CREATE TABLE cancellation_outbox (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    idempotency_key     TEXT NOT NULL UNIQUE,
                              -- {taskId}:{cancellationVersion}:{sessionId}:{processGeneration}
    task_id             UUID NOT NULL REFERENCES task(id),
    session_id          UUID NOT NULL REFERENCES session(id), -- 每个目标 session 一条命令
    worker_id           TEXT NOT NULL REFERENCES worker(id),
    launch_id           UUID NOT NULL,
    cancellation_version BIGINT NOT NULL,            -- Task 取消请求代次
    process_generation  BIGINT NOT NULL,             -- 目标进程代次快照
    status              TEXT NOT NULL DEFAULT 'PENDING',
                                -- PENDING/DISPATCHED/ACKNOWLEDGED/FAILED
    attempt             INTEGER NOT NULL DEFAULT 0,
    next_attempt_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_error_code     TEXT,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    acknowledged_at     TIMESTAMPTZ,
    UNIQUE (task_id, cancellation_version, session_id, process_generation),
    FOREIGN KEY (session_id, process_generation, launch_id)
        REFERENCES session_process(session_id, process_generation, launch_id)
);

CREATE INDEX idx_cancellation_outbox_pending
    ON cancellation_outbox(status, next_attempt_at)
    WHERE status IN ('PENDING', 'DISPATCHED', 'FAILED');
```

取消请求事务中先锁 Task，将 `cancellation_version` 原子 +1、Task 置为 `CANCELLING`，再枚举当时的
全部非终态后代 session，为每个 session 写一条 outbox，并快照其 `fencing_generation`。
Worker 仅在命令的 `process_generation` 和 `launch_id` **都等于**本地该 session 当前进程身份时执行；小于说明
命令迟到，大于说明状态未对账，均拒绝并请求 reconcile。重连后中心重发未确认记录。

若取消事务之后又发现属于该 Task 的新 session，编排器不得启动它；若进程已因竞态启动，则使用同一
`cancellation_version` 为其追加一条 outbox。Task 只有在所有后代 session 均终止且命令已确认后
才能进入 `CANCELLED`。

---

### 聚合状态、事件与 Outbox 的事务规则

所有聚合状态转换统一遵守：

```text
短事务：SELECT FOR UPDATE 或 WHERE version=:expectedVersion
→ 修改聚合状态并 version+1
→ 插入 domain_event
→ 必要时插入 outbox
→ COMMIT
→ 事务外执行 Worker/provider/对象存储/IM 网络 I/O
→ 新短事务 CAS 写回结果
```

只有状态转换的 CAS winner 可以发送后续命令或事件。禁止在数据库事务中等待任何网络 I/O，防止
`idle in transaction`、连接池耗尽和外部超时扩大锁持有时间。后台 terminal-state reconciler 扫描长期
非终态 Invocation，结合 Worker event receipt 和进程状态补写唯一终态；终态写回失败不得 silent catch。

---

### domain_event（持久化领域事件，SSE Last-Event-ID 续传和事件排序依赖此表）

```sql
CREATE TABLE domain_event (
    event_id        BIGSERIAL PRIMARY KEY,           -- 单调递增，不用时间戳排序（多机时钟漂移）
    aggregate_type  TEXT NOT NULL,                   -- session / task / a2a_message / ...
    aggregate_id    UUID NOT NULL,
    trace_id        UUID,
    event_type      TEXT NOT NULL,                   -- 对应 EventType 枚举
    schema_version  INTEGER NOT NULL DEFAULT 1,
    payload         JSONB NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()  -- immutable record
);

CREATE INDEX idx_domain_event_aggregate ON domain_event(aggregate_type, aggregate_id);
CREATE INDEX idx_domain_event_trace ON domain_event(trace_id);
-- SSE 断线重连用 Last-Event-ID（= event_id），按此顺序续传
```

---

### checkpoint_execution（Checkpoint 验证结果，M2 任务编排依赖）

```sql
CREATE TABLE checkpoint_execution (
    id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    task_id                 UUID NOT NULL REFERENCES task(id),
    checkpoint_id           TEXT NOT NULL,           -- 对应 Task.checkpoints[].id
    status                  TEXT NOT NULL DEFAULT 'PENDING',  -- PENDING/VERIFYING/PASSED/FAILED
    evidence_artifact_id    UUID REFERENCES artifact(id),     -- agent 提交的 Evidence
    attempt                 INTEGER NOT NULL DEFAULT 1,
    verification_result     JSONB,                   -- 编排层独立验证的输出
    notes                   TEXT,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (task_id, checkpoint_id, attempt)
);
```

---

### 数据库约束不是应用层备注

V001 必须为固定枚举状态和基础不变量添加 `CHECK` 约束，至少覆盖：Task/Session/Session Process/
Invocation/A2A/Artifact/Inbox/Outbox 的 status、observability level、recording status、audience、persistence，
以及非负 attempt/depth/token/cost。示例：

```sql
ALTER TABLE invocation ADD CONSTRAINT chk_invocation_status CHECK (status IN
  ('PENDING','RUNNING','SEMANTIC_COMPLETED','FAILED','TIMED_OUT','CANCELLING','CANCELLED'));
ALTER TABLE session_credential ADD CONSTRAINT chk_session_credential_audience
  CHECK (audience IN ('gateway','mcp'));
ALTER TABLE task ADD CONSTRAINT chk_task_not_own_parent CHECK (parent_task_id IS NULL OR parent_task_id <> id);
```

跨行/阶段不变量（如 `recording_status=complete` 时 gap reason 为空、Task SUCCEEDED 必须有通过的 Goal
Evidence）由 application service 同事务验证；其中可表达为 CHECK 的部分仍下沉数据库。M1 验收必须实际
查询约束存在并测试非法写入失败，不能只依赖 Java enum。

---

## V002__memory.sql — 记忆表（M3 时加）

```sql
CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE memory_card (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    workspace_id        UUID NOT NULL REFERENCES workspace(id),
    kind                TEXT NOT NULL,   -- decision / constraint / preference / pitfall / fact / correction
    status              TEXT NOT NULL DEFAULT 'active',   -- active / superseded / archived / disputed
    -- 内容
    content             TEXT NOT NULL,
    embedding           vector(768),     -- nomic-embed-text 768维（本机 Ollama，见 06-memory.md）
    embedding_model     TEXT NOT NULL DEFAULT 'nomic-embed-text',  -- 切换模型时需重建所有 embedding
    -- 来源
    source_session_ids  JSONB NOT NULL DEFAULT '[]',
    -- 价值
    score               NUMERIC(4,3) NOT NULL DEFAULT 0.5,
    retrieved_count     INTEGER NOT NULL DEFAULT 0,
    cited_count         INTEGER NOT NULL DEFAULT 0,
    -- 可见域
    scope               JSONB NOT NULL DEFAULT '{"workspace":"*","agentRoles":["*"],"tags":[]}',
    -- 关系
    supersedes          JSONB NOT NULL DEFAULT '[]',   -- [memoryCardId]
    -- 有效期
    hot                 BOOLEAN NOT NULL DEFAULT false,  -- L2-hot 自动注入，L2-cold 按需检索
    valid_until         TIMESTAMPTZ,
    review_after        TIMESTAMPTZ,
    -- 时间
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_memory_workspace ON memory_card(workspace_id, status);
CREATE INDEX idx_memory_hot ON memory_card(workspace_id, hot) WHERE hot = true AND status = 'active';
-- HNSW 不是 IVFFlat：动态构建，数据量小时也好用，查询更快
-- IVFFlat 需要预设 lists 数量，冷启动性能差
CREATE INDEX idx_memory_embedding ON memory_card
    USING hnsw (embedding vector_cosine_ops)
    WITH (m = 16, ef_construction = 64);
```

---

## Migration 与里程碑边界

```text
V001__core_schema.sql  完整核心关系结构（M1/M2 表全部存在，外键一次闭环）
V002__memory.sql       M3：pgvector extension + memory_card
后续 V003+            只做向前兼容的增量结构变更
```

V001 的可执行建表顺序：

```text
workspace
→ workspace_root
→ provider_route
→ agent_profile
→ agent_profile_version
→ worker
→ worker_credential
→ task
→ budget_node
→ ALTER task ADD CONSTRAINT root_budget_id → budget_node(id)
→ session
→ session_process
→ invocation
→ worker_event_receipt
→ session_credential
→ exchange
→ artifact
→ artifact_reference
→ a2a_message
→ inbox_item
→ operation_log
→ checkpoint_execution
→ cancellation_outbox
→ domain_event
```

M1 **只使用** Workspace Root、Provider Route、Profile、Worker/Worker Credential、standalone Session、
Session Process、Invocation、Worker Event Receipt、Session Credential、Exchange、Artifact 和 Domain Event；
Task/A2A/Inbox/Checkpoint 表已存在但没有应用入口。M2 开启编排入口后才写入这些表。
`session.task_id` 和 `artifact.task_id` 永久允许为空，以支持 standalone session；M2 创建的编排记录
由应用服务验证其非空。不存在“先引用未来表、以后再补目标表”的 migration 状态。

Flyway 验收：对全新 PostgreSQL 16 数据库执行 `migrate` 必须一次成功；随后验证所有 FK 目标、
索引和约束均存在，再启动应用。
