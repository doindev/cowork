-- Per-agent delta cursor (survives restarts) and pending session-refresh flag.
ALTER TABLE participant ADD COLUMN last_seen_at timestamptz;
ALTER TABLE participant ADD COLUMN session_reset_requested boolean NOT NULL DEFAULT false;
