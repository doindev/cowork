CREATE EXTENSION IF NOT EXISTS timescaledb;

-- Append-only hypertable; deliberately no FKs in or out (Timescale restriction).
CREATE TABLE message (
    id                    uuid NOT NULL DEFAULT gen_random_uuid(),
    conversation_id       uuid NOT NULL,
    sender_participant_id uuid,
    sender_name           text NOT NULL,
    kind                  text NOT NULL DEFAULT 'CHAT',
    content               text NOT NULL,
    mentions              text[] NOT NULL DEFAULT '{}',
    round                 int NOT NULL DEFAULT 0,
    ref_id                uuid,
    created_at            timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (id, created_at)
);

SELECT create_hypertable('message', 'created_at', chunk_time_interval => INTERVAL '7 days');

CREATE INDEX idx_message_conversation_time ON message (conversation_id, created_at DESC);
CREATE INDEX idx_message_content_fts ON message USING gin (to_tsvector('english', content));
