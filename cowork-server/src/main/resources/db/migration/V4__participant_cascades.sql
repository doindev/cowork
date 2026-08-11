-- Conversation deletion cascades through participants; votes and proposals that
-- reference those participants must cascade too, or the delete is blocked.

ALTER TABLE vote DROP CONSTRAINT vote_voter_participant_id_fkey;
ALTER TABLE vote ADD CONSTRAINT vote_voter_participant_id_fkey
    FOREIGN KEY (voter_participant_id) REFERENCES participant(id) ON DELETE CASCADE;

ALTER TABLE proposal DROP CONSTRAINT proposal_proposer_participant_id_fkey;
ALTER TABLE proposal ADD CONSTRAINT proposal_proposer_participant_id_fkey
    FOREIGN KEY (proposer_participant_id) REFERENCES participant(id) ON DELETE CASCADE;
