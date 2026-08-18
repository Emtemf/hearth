CREATE EXTENSION IF NOT EXISTS pgcrypto;

CREATE TABLE hearth_session (
    id UUID PRIMARY KEY,
    agent_role TEXT NOT NULL,
    status TEXT NOT NULL,
    observability_level TEXT NOT NULL,
    effective_model TEXT NOT NULL,
    latest_system_prompt TEXT,
    recording_status TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_hearth_session_recording_status
        CHECK (recording_status IN ('complete', 'partial', 'failed'))
);

CREATE TABLE hearth_transcript_turn (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    session_id UUID NOT NULL REFERENCES hearth_session(id) ON DELETE CASCADE,
    ordinal INTEGER NOT NULL,
    role TEXT NOT NULL,
    content TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    UNIQUE (session_id, ordinal)
);

CREATE TABLE hearth_exchange (
    id UUID PRIMARY KEY,
    session_id UUID NOT NULL REFERENCES hearth_session(id) ON DELETE CASCADE,
    requested_model TEXT,
    effective_model TEXT NOT NULL,
    input_tokens BIGINT,
    output_tokens BIGINT,
    recording_status TEXT NOT NULL,
    recording_gap_reason TEXT,
    latency_ms BIGINT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_hearth_exchange_recording_status
        CHECK (recording_status IN ('complete', 'partial', 'failed')),
    CONSTRAINT ck_hearth_exchange_gap
        CHECK ((recording_status = 'complete' AND recording_gap_reason IS NULL)
            OR (recording_status <> 'complete' AND recording_gap_reason IS NOT NULL))
);

CREATE INDEX idx_hearth_transcript_turn_session_ordinal
    ON hearth_transcript_turn(session_id, ordinal);
CREATE INDEX idx_hearth_exchange_session
    ON hearth_exchange(session_id, created_at);
