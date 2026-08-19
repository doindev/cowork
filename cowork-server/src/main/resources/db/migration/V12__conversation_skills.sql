-- Per-conversation skill overrides. A row is an explicit user toggle; skills without a
-- row fall back to their file's phase defaults.
CREATE TABLE conversation_skill (
    id uuid PRIMARY KEY,
    conversation_id uuid NOT NULL REFERENCES conversation(id) ON DELETE CASCADE,
    skill_name varchar(100) NOT NULL,
    active boolean NOT NULL,
    updated_at timestamptz NOT NULL,
    UNIQUE (conversation_id, skill_name)
);
