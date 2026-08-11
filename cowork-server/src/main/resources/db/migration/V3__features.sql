-- Feature batch: git diff review, cost tracking, activity transcripts.

ALTER TABLE proposal ADD COLUMN commit_hash text;

ALTER TABLE message ADD COLUMN cost_usd double precision;
ALTER TABLE message ADD COLUMN activity text;

ALTER TABLE conversation ADD COLUMN budget_usd double precision;
ALTER TABLE conversation ADD COLUMN spent_usd double precision NOT NULL DEFAULT 0;
