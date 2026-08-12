-- Projects whose workspace is a user-chosen directory outside the managed workspaces dir.
ALTER TABLE project ADD COLUMN external boolean NOT NULL DEFAULT false;
