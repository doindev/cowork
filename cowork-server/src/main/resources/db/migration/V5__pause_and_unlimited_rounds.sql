-- max_agent_rounds 0 now means "no limit" and becomes the default for new conversations.
ALTER TABLE conversation ALTER COLUMN max_agent_rounds SET DEFAULT 0;

-- User-controlled pause switch: while paused, no agent turns are routed or executed.
ALTER TABLE conversation ADD COLUMN paused boolean NOT NULL DEFAULT false;
