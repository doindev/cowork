-- Turns taken on the current CLI session, for optional auto-rotation (max-session-turns).
ALTER TABLE participant ADD COLUMN session_turn_count int NOT NULL DEFAULT 0;
