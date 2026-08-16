# 数据库 Schema

Flyway 管理，文件放 `hearth-core/src/main/resources/db/migration/`。
命名规范：`V{version}__{description}.sql`，version 三位补零（V001、V002…）。

---

## ER 概览（按里程碑演进）

```
workspace ──< workspace_root
          ──< provider_route ──< provider_pricing
          ──< session ──< session_process
                     ├────< invocation ──< exchange
                     └────< worker_event_receipt

task_request ──< task_spec_version ──< plan_version
             └─< task（TaskExecution，自引用业务树）──< session
                       ├──< budget_node（自引用划拨树）
                       └──< dispatch_message

evidence_claim ──< verification_record ──< verification_artifact >── artifact

session ──< invocation ──< exchange
        ──< worker_command
        ──< artifact ──< artifact_reference
        ──< session_credential
worker ──< worker_credential
       ──< session

task ──< cancellation_outbox
inbox_item ──> task / session（外键可空）
memory_card ──> source_sessions（JSONB 数组）
domain_event ── event_publication（提交后 SSE 可见顺序）
```

---

## Migration 原则

Migration 按已验收的 vertical slice 演进，不在 V001 预建尚未验证的 M2 世界。Flyway migration 是不可修改的
历史：某个版本进入共享环境后只新增后续 migration，禁止回写旧文件。里程碑仍由应用入口控制，但未来表
不需要为了“外键一次闭环”提前存在；引用和目标在引入该能力的同一个 migration 中创建即可。

```text
V001  M1 data-plane + standalone Session
V002  M1 Worker lifecycle / command / replay / credential
V003  M2 TaskRequest + TaskSpecVersion + PlanVersion + TaskExecution + PolicyBundleVersion
V004  M2 internal dispatch + consumable budget
V005  M2 Artifact + EvidenceClaim + VerificationRecord + Inbox/operation log/cancellation
V006  M3 automation trigger extensions + memory/pgvector（可按实现 slice 继续细分）
```

下面的 SQL 是目标结构草案；真正实现时按上述 migration 拆分，并为每个 migration 单独做空库 migrate、
upgrade、validate 和非法约束写入测试。

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

### admin_bootstrap_credential / admin_session（M1 本地管理面认证）

```sql
CREATE TABLE admin_bootstrap_credential (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    token_hash  BYTEA NOT NULL UNIQUE,
    expires_at  TIMESTAMPTZ NOT NULL,
    consumed_at TIMESTAMPTZ,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE admin_session (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    session_token_hash  BYTEA NOT NULL UNIQUE,
    csrf_token_hash     BYTEA NOT NULL,
    expires_at          TIMESTAMPTZ NOT NULL,
    revoked_at          TIMESTAMPTZ,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_used_at        TIMESTAMPTZ
);
```

两类 token 都是 256-bit CSPRNG opaque value，数据库只存 SHA-256。bootstrap credential 单次消费；
管理员 cookie 和 CSRF token 独立，logout/过期后撤销。表中不存 bootstrap 明文或浏览器 cookie 明文。

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
    UNIQUE (workspace_id, canonical_path),
    UNIQUE (id, workspace_id)
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
    UNIQUE (workspace_id, name),
    UNIQUE (id, workspace_id)
);

CREATE TABLE provider_pricing (
    id                    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    provider_route_id     UUID NOT NULL REFERENCES provider_route(id),
    model                 TEXT NOT NULL,
    version               INTEGER NOT NULL,
    currency              TEXT NOT NULL DEFAULT 'USD',
    rates                 JSONB NOT NULL,
                          -- input/output/cache read/cache write 及可能的 context tier 单价
    effective_from        TIMESTAMPTZ NOT NULL,
    effective_until       TIMESTAMPTZ,
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (provider_route_id, model, version),
    UNIQUE (id, provider_route_id, model)
);
```

Session 创建 API 只接受这两张表的 ID 和相对路径。服务端解析 route 后固定目标 host、wire protocol、
模型白名单和 credential reference；解析 workspace root 后 canonicalize 路径并拒绝 `..`、符号链接逃逸
和根目录之外路径。M1 只做同 wire protocol 模型路由，不承担 Anthropic/OpenAI/Gemini 之间的协议翻译。

---

### policy_bundle_version / agent_profile / agent_profile_version

```sql
CREATE TABLE agent_profile (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    workspace_id UUID NOT NULL REFERENCES workspace(id),
    role        TEXT NOT NULL,                  -- architect / coder / reviewer / assistant
    current_version_id UUID,                   -- 指向最新版本，启动后更新
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (workspace_id, role),
    UNIQUE (id, workspace_id)
);

CREATE TABLE policy_bundle_version (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    workspace_id    UUID NOT NULL REFERENCES workspace(id),
    bundle_key      TEXT NOT NULL,                 -- coding-safe / research / assistant
    version         INTEGER NOT NULL,
    policies        JSONB NOT NULL,                -- normalized capability policy，非 adapter tool 名
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (workspace_id, bundle_key, version),
    UNIQUE (id, workspace_id)
);

CREATE TABLE agent_profile_version (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    workspace_id    UUID NOT NULL REFERENCES workspace(id),
    profile_id      UUID NOT NULL,
    version         INTEGER NOT NULL,
    system_prompt   TEXT NOT NULL,
    capabilities    JSONB NOT NULL DEFAULT '[]',   -- ["filesystem.read","codegraph.query",...]
    constraints     JSONB NOT NULL DEFAULT '[]',   -- [{rule, disposition}]
    policy_bundle_version_id UUID REFERENCES policy_bundle_version(id),
    adapter_type    TEXT NOT NULL,                  -- claude-code/codex/gemini/opencode/pi-rpc
    required_governance TEXT NOT NULL DEFAULT 'OBSERVE_ONLY',
    preferred_model TEXT NOT NULL,
    memory_scope    JSONB NOT NULL DEFAULT '{}',   -- {workspace, agentRoles, tags}
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (profile_id, version),
    UNIQUE (id, profile_id),
    FOREIGN KEY (profile_id, workspace_id) REFERENCES agent_profile(id, workspace_id),
    FOREIGN KEY (policy_bundle_version_id, workspace_id)
        REFERENCES policy_bundle_version(id, workspace_id)
);

ALTER TABLE agent_profile ADD CONSTRAINT fk_agent_profile_current_version
    FOREIGN KEY (current_version_id, id) REFERENCES agent_profile_version(id, profile_id);
```

---

Adapter 在启动时把 normalized capability 映射到具体 CLI/MCP 工具名；Policy 可表达「代码探索必须先用
`codegraph.query`」「第三方 API 事实必须由 `context7.docs` 或 `official_docs.search` 验证」。核心领域和
Profile 不保存 Claude/Codex 专属 tool name。

### task_request / task_spec_version / plan_version / task（M2）

```sql
CREATE TABLE task_request (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    workspace_id    UUID NOT NULL REFERENCES workspace(id),
    title           TEXT NOT NULL,
    brief           TEXT NOT NULL,
    source_kind     TEXT NOT NULL,              -- manual/cron/webhook/inbound_message/dispatch_request
    source_ref      TEXT NOT NULL,              -- audit event / external source reference
    external_event_id TEXT,                     -- source 提供的稳定 ID；优先用于幂等
    fallback_dedupe_key TEXT,                   -- source 无 ID 时的短期 fallback，不使用分钟窗语义
    status          TEXT NOT NULL DEFAULT 'INTAKE', -- INTAKE/CLARIFYING/SPECIFIED/REJECTED/CANCELLED
    version         BIGINT NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (id, workspace_id)
);

CREATE UNIQUE INDEX uq_task_request_external_event
    ON task_request(workspace_id, source_kind, external_event_id)
    WHERE external_event_id IS NOT NULL;

CREATE TABLE task_spec_version (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    request_id          UUID NOT NULL REFERENCES task_request(id),
    version             INTEGER NOT NULL,
    goal                TEXT NOT NULL,
    context_items       JSONB NOT NULL DEFAULT '[]',
    context_gaps        JSONB NOT NULL DEFAULT '[]',
    acceptance_criteria JSONB NOT NULL DEFAULT '[]',
    constraints         JSONB NOT NULL DEFAULT '[]',
    evidence_policy     JSONB NOT NULL,
    created_by_kind     TEXT NOT NULL,           -- HUMAN/SESSION/SYSTEM
    created_by_ref      TEXT NOT NULL,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (request_id, version),
    UNIQUE (id, request_id)
);

CREATE TABLE plan_version (
    id                    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    spec_version_id       UUID NOT NULL REFERENCES task_spec_version(id),
    version               INTEGER NOT NULL,
    decision              JSONB NOT NULL,
    alternatives          JSONB NOT NULL DEFAULT '[]',
    trade_offs            JSONB NOT NULL DEFAULT '[]',
    subtask_templates     JSONB NOT NULL DEFAULT '[]',
    checkpoints           JSONB NOT NULL DEFAULT '[]',
    correction_policy     JSONB NOT NULL,
    created_by_session_id UUID,                  -- session FK 在双方表存在后添加
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (spec_version_id, version),
    UNIQUE (id, spec_version_id)
);

CREATE TABLE task (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(), -- domain: TaskExecution
    workspace_id    UUID NOT NULL REFERENCES workspace(id),
    request_id      UUID NOT NULL REFERENCES task_request(id),
    spec_version_id UUID NOT NULL REFERENCES task_spec_version(id),
    plan_version_id UUID NOT NULL REFERENCES plan_version(id),
    parent_task_id  UUID REFERENCES task(id),
    -- 触发快照
    trigger_kind    TEXT NOT NULL,              -- manual / cron / webhook / inbound_message
    trigger_config  JSONB NOT NULL DEFAULT '{}',
    -- 生命周期
    status          TEXT NOT NULL DEFAULT 'READY',
                    -- READY/RUNNING/VERIFYING/AWAITING_HUMAN/CANCELLING/SUCCEEDED/FAILED/CANCELLED
    version         BIGINT NOT NULL DEFAULT 0,
    cancellation_version BIGINT NOT NULL DEFAULT 0, -- 每次用户取消原子 +1
    persistence     TEXT NOT NULL DEFAULT 'ephemeral',  -- ephemeral / durable
    root_budget_id  UUID,                       -- 指向根 budget_node
    target_agent_role TEXT,
    trace_id        UUID NOT NULL DEFAULT gen_random_uuid(),
    -- 时间
    deadline_at     TIMESTAMPTZ NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    ended_at        TIMESTAMPTZ,
    UNIQUE (id, workspace_id),
    UNIQUE (id, spec_version_id),
    FOREIGN KEY (request_id, workspace_id) REFERENCES task_request(id, workspace_id),
    FOREIGN KEY (spec_version_id, request_id) REFERENCES task_spec_version(id, request_id),
    FOREIGN KEY (plan_version_id, spec_version_id) REFERENCES plan_version(id, spec_version_id)
);

CREATE INDEX idx_task_workspace_status ON task(workspace_id, status);
CREATE INDEX idx_task_parent_id ON task(parent_task_id);
CREATE INDEX idx_task_trace_id ON task(trace_id);
ALTER TABLE task ADD CONSTRAINT fk_task_parent_same_workspace
    FOREIGN KEY (parent_task_id, workspace_id) REFERENCES task(id, workspace_id);
```

`TaskRequest` 允许只有 brief；`TaskSpecVersion` 与 `PlanVersion` 发布后不可更新。用户改需求时创建新 spec，
重新规划时创建新 plan。运行中的 Task 固定引用版本，禁止原地换目标。`request` dispatch 创建 Child
TaskRequest/Task；`consult` 不创建 Task。

入口幂等优先使用来源原生 `external_event_id`（飞书 event/message ID、Telegram update ID、webhook event ID、
schedule fire ID）。来源确实没有 ID 时才计算带短 TTL 的 `fallback_dedupe_key`；命中只能标记 suspected
duplicate 并走来源策略/人工确认，不能仅因同一分钟两条文字相同就静默丢弃。

---

### budget_node

```sql
CREATE TABLE budget_node (
    id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    task_id                 UUID NOT NULL REFERENCES task(id),
    parent_id               UUID REFERENCES budget_node(id),   -- NULL = 根节点
    -- 预算
    granted_tokens          BIGINT NOT NULL DEFAULT 0,
    granted_usd             NUMERIC(12,6) NOT NULL DEFAULT 0,
    -- 消耗
    spent_tokens            BIGINT NOT NULL DEFAULT 0,
    spent_usd               NUMERIC(12,6) NOT NULL DEFAULT 0,
    -- 划拨给子节点的总量
    allocated_tokens        BIGINT NOT NULL DEFAULT 0,
    allocated_usd           NUMERIC(12,6) NOT NULL DEFAULT 0,

    created_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (id, task_id)
);

ALTER TABLE task
    ADD CONSTRAINT fk_task_root_budget_same_task
    FOREIGN KEY (root_budget_id, id) REFERENCES budget_node(id, task_id);
```

token/USD 是可消耗、可划拨预算；扣除使用 `SELECT FOR UPDATE`。wall time 不进入 allocation ledger：Task 使用
绝对 `deadline_at`（根 Task 根据运行时限计算，Child deadline 不得晚于父），Session/Invocation timeout 是
执行策略。M2 初期聚合计数可作为锁内真相；稳定后增加 append-only `budget_ledger`，聚合字段降为 cache。

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
    adapter_type        TEXT NOT NULL,
    adapter_version     INTEGER NOT NULL,
    process_reuse_policy TEXT NOT NULL, -- STREAMING_STDIN / RESUME_PER_INVOCATION
    -- 观测
    observability_level  TEXT NOT NULL,   -- full / sidecar / none
    observability_reason TEXT NOT NULL,   -- proxied / subscription_auth / ...
    -- 模型路由（受信配置引用；快照字段用于历史解释）
    provider_route_id   UUID NOT NULL REFERENCES provider_route(id),
    pricing_id          UUID REFERENCES provider_pricing(id),
    requested_model     TEXT,
    effective_model     TEXT NOT NULL,
    upstream_base_url   TEXT NOT NULL,
    wire_protocol       TEXT NOT NULL,    -- anthropic / openai-responses / gemini / ...
    gateway_auth_carrier TEXT,            -- authorization_bearer / x_api_key / ...；非代理模式可空
    -- Worker
    worker_id           TEXT REFERENCES worker(id),
    -- 生命周期
    status              TEXT NOT NULL DEFAULT 'PENDING',
    terminal_reason     TEXT, -- TERMINATED/CRASHED 时解释 completed/cancelled/timed_out/failed/replaced
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
    UNIQUE (id, task_id),
    UNIQUE (id, workspace_id),
    FOREIGN KEY (task_id, workspace_id) REFERENCES task(id, workspace_id),
    FOREIGN KEY (agent_profile_id, workspace_id) REFERENCES agent_profile(id, workspace_id),
    FOREIGN KEY (profile_version_id, agent_profile_id)
        REFERENCES agent_profile_version(id, profile_id),
    FOREIGN KEY (provider_route_id, workspace_id) REFERENCES provider_route(id, workspace_id),
    FOREIGN KEY (pricing_id, provider_route_id, effective_model)
        REFERENCES provider_pricing(id, provider_route_id, model),
    FOREIGN KEY (workspace_root_id, workspace_id) REFERENCES workspace_root(id, workspace_id),
    FOREIGN KEY (budget_node_id, task_id) REFERENCES budget_node(id, task_id)
);

CREATE INDEX idx_session_task_id ON session(task_id);
CREATE INDEX idx_session_trace_id ON session(trace_id);
CREATE INDEX idx_session_status ON session(status) WHERE status NOT IN ('TERMINATED','CRASHED');
```

`task_id` 在 M1 standalone session 中允许为 `NULL`，M2 编排 session 必须由应用层保证非空；
不在数据库层全局改成 `NOT NULL`，否则会破坏长期保留的手动 session 能力。
`gateway_auth_carrier` 是服务端按 `adapter_type + wire_protocol` 从已通过 contract test 的 allowlist 生成的
`GatewayIngressAuthBinding` 快照；Session API/Agent/Worker 不得自由提交。它只记录 carrier 类型，不记录 token；
Gateway 必须从该 carrier 认证后剥离所有内部/客户端 credential，再注入服务端上游凭证。
`resume_descriptor` 是 adapter 专属的带版本结构，禁止把未知裸字符串直接传给 CLI。恢复时重新解析
`provider_route.credential_ref` 当前指向的 secret；不在 Session 中冻结或持久化 provider secret。
Session 状态的唯一词表是 `PENDING/LAUNCHING/READY/ACTIVE/TERMINATING/TERMINATED/CRASHED`；业务完成、
取消、超时和失败放在 `terminal_reason`，不再把 `COMPLETED/RUNNING/CANCELLED` 混作 Session status。

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
    diagnostic_ref        TEXT, -- M1 受限诊断存储引用；只含脱敏 stderr 摘要，不是 Artifact
    started_at            TIMESTAMPTZ,
    semantic_completed_at TIMESTAMPTZ,
    ended_at              TIMESTAMPTZ,
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (session_id, command_id, attempt),
    UNIQUE (id, session_id),
    FOREIGN KEY (session_id, process_generation)
        REFERENCES session_process(session_id, process_generation)
);

CREATE INDEX idx_invocation_session ON invocation(session_id, created_at);
CREATE INDEX idx_invocation_non_terminal ON invocation(status)
    WHERE status NOT IN ('SEMANTIC_COMPLETED','FAILED','TIMED_OUT','CANCELLED');
CREATE UNIQUE INDEX uq_invocation_one_active_per_session ON invocation(session_id)
    WHERE status NOT IN ('SEMANTIC_COMPLETED','FAILED','TIMED_OUT','CANCELLED');
```

Session 是可跨多轮、可换进程代次的逻辑会话；Invocation 才是一次 user turn 或编排激活。
`semantic_completed`、`stream_eof`、`process_exited` 和 `transport_disconnected` 分别记录，禁止因为 CLI
stdout 仍打开而把已经完成的 Invocation 误判为超时，也禁止因为 transport 断开就假定进程死亡。

M1/M2 默认禁止同一 Session 同时存在两个非终态 Invocation。创建 Invocation 的短事务先建立这条唯一的
active binding，再在提交后调用 `WorkerClient.invoke`；Gateway 收到 `/s/{sessionId}/{protocol}` 请求时只归属
到该 Session 唯一的非终态 Invocation。没有 active Invocation 时返回 `409 gateway.no_active_invocation`；
出现多条属于数据损坏，返回 `503 gateway.invocation_binding_corrupt` 并告警，禁止猜“最近的一条”。未来如需
并发 Invocation，必须升级 URL/header correlation 契约，不能取消此约束后继续靠 sessionId 猜测。

### worker_command（LAUNCH/INVOKE/standalone CANCEL 的 durable claim）

```sql
CREATE TABLE worker_command (
    command_id          UUID PRIMARY KEY,
    kind                TEXT NOT NULL, -- LAUNCH/INVOKE/CANCEL
    session_id          UUID NOT NULL,
    process_generation  BIGINT NOT NULL,
    launch_id           UUID NOT NULL,
    invocation_id       UUID,
    payload_hash        TEXT NOT NULL, -- 同 commandId 不允许换 payload
    commit_state        TEXT,          -- COMMITTED/...；发送前为 NULL
    result_code         TEXT,
    result_payload      JSONB,
    attempt             INTEGER NOT NULL DEFAULT 0,
    next_attempt_at     TIMESTAMPTZ,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    committed_at        TIMESTAMPTZ,
    FOREIGN KEY (session_id, process_generation, launch_id)
        REFERENCES session_process(session_id, process_generation, launch_id),
    FOREIGN KEY (invocation_id, session_id) REFERENCES invocation(id, session_id)
);

CREATE INDEX idx_worker_command_retry ON worker_command(next_attempt_at)
    WHERE commit_state IS NULL;
CREATE UNIQUE INDEX uq_worker_command_one_launch_per_process
    ON worker_command(session_id, process_generation, launch_id)
    WHERE kind = 'LAUNCH';
```

Session/Invocation 状态变更、`worker_command` claim 和 `domain_event` 在同一短事务提交，之后才做 Worker I/O。
相同 `commandId` 但 `payload_hash` 不同返回 `409 worker.command_payload_mismatch`。Task 级级联取消仍使用独立
高优先级 `cancellation_outbox`，但每条 outbox 同时引用一个稳定 command claim。`session_process` 不反向保存
`launch_command_id`，避免首条 LAUNCH 与进程代次形成不可插入的循环外键；上面的部分唯一索引保证每个代次只有
一个 LAUNCH claim。

### worker_event_receipt（控制面事件重放水位）

```sql
CREATE TABLE worker_event_receipt (
    session_id         UUID NOT NULL REFERENCES session(id),
    process_generation BIGINT NOT NULL,
    launch_id          UUID NOT NULL,
    last_event_seq     BIGINT NOT NULL DEFAULT 0,
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (session_id, process_generation),
    FOREIGN KEY (session_id, process_generation, launch_id)
        REFERENCES session_process(session_id, process_generation, launch_id)
);
```

Worker 对每代进程生成从 1 单调递增的 `eventSeq`，本地持久化未确认事件；中心按此表的水位去重、确认
并在断线后请求重放。业务可见顺序使用提交后分配的 `event_publication.publication_seq`，两种序号用途不同。

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

创建 Session 时只为已启用 audience 签发独立的 256-bit CSPRNG opaque token：M1 只签 gateway，M2 启用
Platform MCP 后再签 MCP；两者永不复用。明文只进入受保护的 Worker launch 环境一次。认证时对收到的 token
做 SHA-256 后等值查询，并同时验证
`session_id`、`worker_id`、`audience`、`expires_at`、`revoked_at`。token 不携带可解析元数据，
也不复用上游 provider credential。

---

### exchange（网关捕获的每次模型调用）

```sql
CREATE TABLE exchange (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    session_id      UUID NOT NULL REFERENCES session(id),
    invocation_id   UUID NOT NULL,
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
    pricing_snapshot JSONB,             -- 计算时使用的 pricing id/version/rates/currency；usage 缺失时可空
    latency_ms      INTEGER,
    streamed        BOOLEAN,
    stop_reason     TEXT,
    response_body_ref TEXT,
    -- 录制状态（独立于 observability_level：level 是接入机制，status 是实际结果）
    recording_status    TEXT NOT NULL DEFAULT 'complete',   -- complete/partial/failed
    recording_gap_reason TEXT,   -- queue_overflow/storage_failure/parse_failure（partial/failed 时必填）
    -- 时间
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    FOREIGN KEY (invocation_id, session_id)
        REFERENCES invocation(id, session_id)
);

CREATE INDEX idx_exchange_session_id ON exchange(session_id);
CREATE INDEX idx_exchange_invocation_id ON exchange(invocation_id);
CREATE INDEX idx_exchange_trace_id ON exchange(trace_id);
```

请求头解析完成后先插入 exchange；流结束、客户端断开或转发失败时更新响应、token、延迟和
`recording_status`，因此 exchange 是受控更新的调用聚合，不是 immutable event。
`recording_status='complete'` 时 `recording_gap_reason` 必须为 `NULL`；`partial/failed` 时必须有原因。
Anthropic usage 只提供 token，不提供美元账单；`usd` 必须用 Session 选择的 `provider_pricing` 计算，并把
实际 rates/version/currency 冻结进 `pricing_snapshot`。找不到有效 pricing 时 token 仍可记录，但 `usd` 和
snapshot 保持 NULL，API/UI 显示“成本未知”，禁止使用当前价格回填历史记录或声称精确成本。

---

### artifact

```sql
CREATE TABLE artifact (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    session_id      UUID NOT NULL REFERENCES session(id),
    task_id         UUID REFERENCES task(id),               -- NULL in M1; NOT NULL enforced in M2
    command_id      UUID,                  -- MCP/工具创建时的稳定幂等键；系统内部导入可空
    type            TEXT NOT NULL,      -- code_change / document / test_result / ...
    status          TEXT NOT NULL DEFAULT 'PENDING_UPLOAD',
                                    -- PENDING_UPLOAD/AVAILABLE/UPLOAD_FAILED/DELETED/SECURITY_PURGED
    title           TEXT,
    content         TEXT,               -- 小文件直接存（< 64KB）
    storage_ref     TEXT,               -- 大文件存对象存储，路径在这里
    size_bytes      BIGINT,
    sha256          TEXT,               -- AVAILABLE 后必填；删除后保留用于核验 tombstone
    metadata        JSONB NOT NULL DEFAULT '{}',
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at      TIMESTAMPTZ,
    UNIQUE (session_id, command_id),
    UNIQUE (id, task_id),
    FOREIGN KEY (session_id, task_id) REFERENCES session(id, task_id)
);

CREATE INDEX idx_artifact_session_id ON artifact(session_id);
CREATE INDEX idx_artifact_task_id ON artifact(task_id);
CREATE INDEX idx_artifact_cleanup ON artifact(status, created_at);
```

`PENDING_UPLOAD → AVAILABLE | UPLOAD_FAILED`；失败可重试并回到 `PENDING_UPLOAD`。
普通清理只允许 `AVAILABLE → DELETED`，同时清空 `content/storage_ref`，保留同一行的
`id/sha256/size_bytes/deleted_at/metadata` 作为 tombstone。删除前必须确认 `artifact_reference`
计数为 0。安全/泄密事件允许管理员执行 `SECURITY_PURGE`，即使有引用也清除内容并保留最小 tombstone；
同一事务把依赖该 Artifact 的 VerificationRecord 置为 `INVALIDATED` 并记录安全审计事件。普通 agent 无权调用。

### artifact_reference

```sql
CREATE TABLE artifact_reference (
    artifact_id        UUID NOT NULL REFERENCES artifact(id),
    referrer_type      TEXT NOT NULL, -- verification_record/dispatch_message/inbox/operation_log/memory_card/domain_event
    referrer_id        TEXT NOT NULL,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (artifact_id, referrer_type, referrer_id)
);

CREATE INDEX idx_artifact_reference_referrer
    ON artifact_reference(referrer_type, referrer_id);
```

引用创建/删除与引用方业务写入在同一事务中完成；清理器用行锁再次检查零引用，避免检查后新增引用的竞态。

---

### dispatch_message（Hearth 内部领域模型）

```sql
CREATE TABLE dispatch_message (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    command_id          UUID NOT NULL, -- 可信 MCP/Adapter 边界生成或确定性派生
    source_task_id      UUID NOT NULL REFERENCES task(id),
    child_task_id       UUID REFERENCES task(id), -- REQUEST 投递完成后必须有；其他 kind 必须为空
    from_session_id     UUID NOT NULL REFERENCES session(id),
    target_type         TEXT, -- SPAWN_PROFILE / EXISTING_SESSION；ESCALATE 无目标
    target_profile_id   UUID REFERENCES agent_profile(id),
    to_session_id       UUID REFERENCES session(id), -- 初始可空，目标 Session 创建后回填
    kind                TEXT NOT NULL,  -- REQUEST / CONSULT / NOTIFY / ESCALATE
    content             TEXT NOT NULL,
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
    acknowledged_at     TIMESTAMPTZ,
    UNIQUE (from_session_id, command_id),
    UNIQUE (trace_id, span_id),
    FOREIGN KEY (from_session_id, source_task_id) REFERENCES session(id, task_id)
);

CREATE INDEX idx_dispatch_trace_id ON dispatch_message(trace_id);
CREATE INDEX idx_dispatch_from_session ON dispatch_message(from_session_id);

CREATE TABLE dispatch_message_artifact (
    message_id      UUID NOT NULL,
    artifact_id     UUID NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (message_id, artifact_id),
    FOREIGN KEY (message_id) REFERENCES dispatch_message(id) ON DELETE CASCADE,
    FOREIGN KEY (artifact_id) REFERENCES artifact(id)
);
```

Application service 强制：`REQUEST` 只能使用 `SPAWN_PROFILE`，创建 Child TaskRequest/Task 和新 Session 后回填
`child_task_id`；`CONSULT/NOTIFY` 才能使用 `EXISTING_SESSION`，`ESCALATE` 作用于当前 Task；后三者的
`child_task_id` 必须为空。目标字段满足与 kind 对应的互斥约束。外部 A2A wire message 只存在于 adapter，
不得直接持久化为 core domain type。API/MCP 中的 `artifactIds` 是关联表投影；同事务写
`artifact_reference(referrer_type='dispatch_message')`。

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
    escalated_at    TIMESTAMPTZ,
    FOREIGN KEY (task_id, workspace_id) REFERENCES task(id, workspace_id),
    FOREIGN KEY (session_id, workspace_id) REFERENCES session(id, workspace_id)
);

CREATE INDEX idx_inbox_workspace_status ON inbox_item(workspace_id, status);

CREATE TABLE inbox_item_artifact (
    inbox_item_id   UUID NOT NULL REFERENCES inbox_item(id) ON DELETE CASCADE,
    artifact_id     UUID NOT NULL REFERENCES artifact(id),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (inbox_item_id, artifact_id)
);
```

Inbox `actions` 只保存动作定义，不内嵌 Artifact ID；证据列表来自 `inbox_item_artifact`，并在同事务写
`artifact_reference(referrer_type='inbox_item')`。

---

### operation_log（undo 清单基础）

```sql
CREATE TABLE operation_log (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    session_id      UUID NOT NULL REFERENCES session(id),
    invocation_id   UUID NOT NULL,
    task_id         UUID REFERENCES task(id),
    command_id      UUID NOT NULL,
    tool_name       TEXT NOT NULL,
    tool_input      JSONB NOT NULL,
    status          TEXT NOT NULL DEFAULT 'CLAIMED',
                    -- CLAIMED/COMMITTED/COMMITTED_WITH_WARNING/REJECTED_BEFORE_COMMIT/UNKNOWN_COMMIT_STATE
    tool_result     JSONB,
    external_operation_id TEXT,
    -- 文件操作快照（写操作前的内容）
    snapshot_artifact_id UUID REFERENCES artifact(id), -- 操作前内容也是可寻址、受引用保护的 Artifact
    file_path       TEXT,
    -- 时间
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    committed_at    TIMESTAMPTZ,
    UNIQUE (session_id, command_id),
    FOREIGN KEY (session_id, task_id) REFERENCES session(id, task_id),
    FOREIGN KEY (invocation_id, session_id) REFERENCES invocation(id, session_id),
    FOREIGN KEY (snapshot_artifact_id, task_id) REFERENCES artifact(id, task_id)
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
    command_id          UUID NOT NULL UNIQUE REFERENCES worker_command(command_id),
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

### domain_event / event_publication（持久化事件与提交后可见顺序）

```sql
CREATE TABLE domain_event (
    event_id        BIGSERIAL PRIMARY KEY,           -- 数据库内部 ID；不承诺等于事务提交顺序
    aggregate_type  TEXT NOT NULL,                   -- session / task / dispatch_message / ...
    aggregate_id    UUID NOT NULL,
    trace_id        UUID,
    event_type      TEXT NOT NULL,                   -- 对应 EventType 枚举
    schema_version  INTEGER NOT NULL DEFAULT 1,
    payload         JSONB NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()  -- immutable record
);

CREATE INDEX idx_domain_event_aggregate ON domain_event(aggregate_type, aggregate_id);
CREATE INDEX idx_domain_event_trace ON domain_event(trace_id);

CREATE TABLE event_publication (
    publication_seq BIGSERIAL PRIMARY KEY,           -- SSE 唯一可见顺序
    event_id         BIGINT UNIQUE REFERENCES domain_event(event_id) ON DELETE SET NULL,
    published_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);
```

PostgreSQL sequence 值在事务回滚时不会回收，也不等于事务提交顺序：事务 A 可先取得 `event_id=10` 后提交，
事务 B 可取得 11 后先提交。因此 SSE **禁止**直接把 `domain_event.event_id` 当 cursor，否则客户端看见 11 后
可能永久漏掉稍后提交的 10。

M1 使用单一 `EventPublicationService`：它持有 Postgres advisory lock，只扫描已经提交且尚未发布的
`domain_event`，按稳定批次插入 `event_publication`。只有 `publication_seq` 才写入 SSE `id` 和 snapshot
`eventWatermark`。发布器故障只造成事件暂未推送；REST snapshot 仍从聚合表读取真相，发布器恢复后继续。
任何多实例实现都必须保持单 publisher lease，不能让两个发布事务并发分配可见序号。
`event_publication` 是轻量 cursor tombstone，保留时间长于 `domain_event`；事件按保留策略删除后 `event_id`
置空但 publication sequence 不复用。客户端 cursor 落入已删除区间时收到 `cursor.expired` 并拉 snapshot。

---

### evidence_claim / verification_record（Artifact 不等于 Evidence）

```sql
CREATE TABLE evidence_claim (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    task_id             UUID NOT NULL REFERENCES task(id),
    spec_version_id     UUID NOT NULL REFERENCES task_spec_version(id),
    subject_type        TEXT NOT NULL, -- CHECKPOINT/GOAL/ARTIFACT/OPERATION
    subject_ref         TEXT NOT NULL,
    claim_type          TEXT NOT NULL,
    statement           TEXT NOT NULL,
    source_state_ref    JSONB NOT NULL, -- git/tree/diff SHA, workspace root, tool version/command
    submitted_by_session_id UUID REFERENCES session(id),
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (id, task_id),
    FOREIGN KEY (task_id, spec_version_id) REFERENCES task(id, spec_version_id),
    FOREIGN KEY (submitted_by_session_id, task_id) REFERENCES session(id, task_id)
);

CREATE TABLE verification_record (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    claim_id            UUID NOT NULL,
    task_id             UUID NOT NULL REFERENCES task(id),
    attempt             INTEGER NOT NULL,
    verifier_kind       TEXT NOT NULL, -- DETERMINISTIC_TOOL/REVIEWER_AGENT/HUMAN/COMPOSITE
    verifier_ref        TEXT NOT NULL, -- rule id / profileVersion+session / human audit id / composition rule
    method              JSONB NOT NULL,
    source_state_ref    JSONB NOT NULL,
    status              TEXT NOT NULL, -- PENDING/PASSED/FAILED/STALE/INVALIDATED
    result              JSONB NOT NULL DEFAULT '{}',
    verified_at         TIMESTAMPTZ,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (claim_id, attempt),
    UNIQUE (id, task_id),
    FOREIGN KEY (claim_id, task_id) REFERENCES evidence_claim(id, task_id)
);

CREATE TABLE verification_artifact (
    verification_id UUID NOT NULL,
    artifact_id     UUID NOT NULL,
    task_id         UUID NOT NULL REFERENCES task(id),
    purpose         TEXT NOT NULL, -- SUBJECT_OUTPUT/TOOL_OUTPUT/REVIEW_REPORT/SOURCE_SNAPSHOT
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (verification_id, artifact_id),
    FOREIGN KEY (verification_id, task_id) REFERENCES verification_record(id, task_id) ON DELETE CASCADE,
    FOREIGN KEY (artifact_id, task_id) REFERENCES artifact(id, task_id)
);
```

Artifact 只是材料。Task 只能在同一状态转换事务中确认：当前 `spec_version_id` 的全部 Goal claim 都有最新、
非 `STALE/INVALIDATED` 的 `PASSED` VerificationRecord，之后才能进入 `SUCCEEDED`。Reviewer 验证必须引用
独立 reviewer Session 产出的 review Artifact；orchestrator 只执行 deterministic/composite rule，不直接调模型。
`verification_artifact` 与 `artifact_reference(referrer_type='verification_record')` 同事务创建。

---

### 数据库约束不是应用层备注

每个 migration 必须同时为它引入的固定枚举和基础不变量添加 `CHECK` 约束。V001 覆盖 Session、Invocation、
Exchange、observability/recording；V002 覆盖 Worker/Process/Command/Credential；V003+ 分别覆盖 Task、Dispatch、
Artifact/Evidence/Inbox/Outbox，以及非负 attempt/depth/token/cost。示例：

```sql
ALTER TABLE invocation ADD CONSTRAINT chk_invocation_status CHECK (status IN
  ('PENDING','RUNNING','SEMANTIC_COMPLETED','FAILED','TIMED_OUT','CANCELLING','CANCELLED'));
ALTER TABLE session ADD CONSTRAINT chk_session_status CHECK (status IN
  ('PENDING','LAUNCHING','READY','ACTIVE','TERMINATING','TERMINATED','CRASHED'));
ALTER TABLE session ADD CONSTRAINT chk_session_terminal_reason CHECK (
  (status IN ('TERMINATED','CRASHED') AND terminal_reason IS NOT NULL AND ended_at IS NOT NULL)
  OR (status NOT IN ('TERMINATED','CRASHED') AND terminal_reason IS NULL AND ended_at IS NULL));
ALTER TABLE session ADD CONSTRAINT chk_session_process_reuse_policy CHECK
  (process_reuse_policy IN ('STREAMING_STDIN','RESUME_PER_INVOCATION'));
ALTER TABLE session_credential ADD CONSTRAINT chk_session_credential_audience
  CHECK (audience IN ('gateway','mcp'));
ALTER TABLE task ADD CONSTRAINT chk_task_not_own_parent CHECK (parent_task_id IS NULL OR parent_task_id <> id);
```

跨行/阶段不变量（如 `recording_status=complete` 时 gap reason 为空、Task SUCCEEDED 必须有通过的当前
Goal Verification）由 application service 同事务验证；其中可表达为 CHECK 的部分仍下沉数据库。每个 slice
验收必须实际查询本 slice 约束存在并测试非法写入失败，不能只依赖 Java enum。

---

## V006 memory slice — 记忆表（M3 时加）

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
V001__m1_data_plane.sql       workspace/admin/route/profile/standalone session/invocation/exchange/event publication
V002__m1_worker_runtime.sql    worker/process/command/replay/session credential，以及 session 的 Worker 字段
V003__m2_task_model.sql        policy bundle/request/spec/plan/task，以及 session 的 nullable task 关联
V004__m2_dispatch_budget.sql   dispatch message/artifact link/budget，以及 trace 与 task tree 约束
V005__m2_evidence_ops.sql      artifact/evidence/verification/inbox/operation log/cancellation outbox
V006+                         automation、memory/pgvector 等按实现 slice 继续向前迁移
```

关键原则：

- M1 空库只出现 M1 实际读写的表；验收额外断言 `task_request`、`dispatch_message`、`evidence_claim` 不存在。
- V002 必须支持从已含真实 M1 exchange 的 V001 数据库无损升级，而不只是空库成功。
- V003+ 增加关联时，`session.task_id` 与 `artifact.task_id` 永久允许为空，以支持 standalone session；M2
  编排流由 application service 要求非空。
- 一个 migration 内可以先创建双方表再 `ALTER TABLE` 补循环外键；不要求外键目标从项目第一天存在。
- migration 一旦进入共享环境不可修改；设计尚未实现时允许继续修改本规格草案，首个 SQL 落地后按 append-only 演进。

Flyway 验收：每个 slice 都要在 PostgreSQL 16 上验证 `0 → current` 空库迁移和 `previous → current` 带数据升级，
随后执行 `validate`、约束负例与关键查询索引检查。
