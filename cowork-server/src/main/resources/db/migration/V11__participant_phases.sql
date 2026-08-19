-- Phase-scoped participation: an agent can be limited to PLANNING or IMPLEMENTATION.
-- phase_override marks a manual activate/deactivate that sticks until the next phase change.
ALTER TABLE participant ADD COLUMN active_phases varchar(20) NOT NULL DEFAULT 'ALL';
ALTER TABLE participant ADD COLUMN phase_override boolean NOT NULL DEFAULT false;
