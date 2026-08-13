-- When enabled, the orchestrator automatically nudges agents whose conversation
-- stalled before the phase goal was reached (bounded per user message).
ALTER TABLE conversation ADD COLUMN auto_continue boolean NOT NULL DEFAULT true;
