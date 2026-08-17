ALTER TABLE hearth_session
    ADD COLUMN IF NOT EXISTS version BIGINT NOT NULL DEFAULT 0;

CREATE TABLE hearth_invocation (
    id UUID PRIMARY KEY,
    session_id UUID NOT NULL REFERENCES hearth_session(id) ON DELETE CASCADE,
    command_id UUID NOT NULL,
    content TEXT NOT NULL,
    status TEXT NOT NULL,
    assistant_content TEXT,
    semantic_completed_at TIMESTAMPTZ,
    process_exited_at TIMESTAMPTZ,
    transport_status TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (session_id, command_id),
    CONSTRAINT ck_hearth_invocation_status
        CHECK (status IN ('PENDING', 'RUNNING', 'SEMANTIC_COMPLETED', 'FAILED', 'CANCELLING', 'CANCELLED'))
);

CREATE INDEX idx_hearth_invocation_session_created
    ON hearth_invocation(session_id, created_at);
CREATE UNIQUE INDEX uq_hearth_invocation_active
    ON hearth_invocation(session_id)
    WHERE status IN ('PENDING', 'RUNNING', 'CANCELLING');
