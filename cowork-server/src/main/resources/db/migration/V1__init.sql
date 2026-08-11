CREATE TABLE agent_def (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    name        text NOT NULL UNIQUE,
    cli_type    text NOT NULL,
    model       text,
    options     text,
    persona     text,
    description text,
    file_path   text,
    enabled     boolean NOT NULL DEFAULT true,
    updated_at  timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE project (
    id             uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    name           text NOT NULL UNIQUE,
    workspace_path text NOT NULL,
    created_at     timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE conversation (
    id               uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    title            text NOT NULL,
    phase            text NOT NULL DEFAULT 'PLANNING',
    vote_mode        text NOT NULL DEFAULT 'MAJORITY',
    max_agent_rounds int NOT NULL DEFAULT 4,
    user_votes       boolean NOT NULL DEFAULT false,
    project_id       uuid REFERENCES project(id),
    status           text NOT NULL DEFAULT 'ACTIVE',
    created_at       timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE participant (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    conversation_id uuid NOT NULL REFERENCES conversation(id) ON DELETE CASCADE,
    kind            text NOT NULL,
    agent_id        uuid REFERENCES agent_def(id),
    display_name    text NOT NULL,
    mcp_token_hash  text,
    cli_session_id  text,
    active          boolean NOT NULL DEFAULT true,
    joined_at       timestamptz NOT NULL DEFAULT now(),
    UNIQUE (conversation_id, display_name)
);

CREATE TABLE task (
    id                uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    project_id        uuid NOT NULL REFERENCES project(id) ON DELETE CASCADE,
    title             text NOT NULL,
    description       text,
    status            text NOT NULL DEFAULT 'PROPOSED',
    assignee_agent_id uuid REFERENCES agent_def(id),
    origin_proposal_id uuid,
    ordinal           int NOT NULL DEFAULT 0,
    created_at        timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE proposal (
    id                      uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    conversation_id         uuid NOT NULL REFERENCES conversation(id) ON DELETE CASCADE,
    proposer_participant_id uuid NOT NULL REFERENCES participant(id),
    type                    text NOT NULL,
    title                   text NOT NULL,
    body                    text,
    status                  text NOT NULL DEFAULT 'OPEN',
    task_id                 uuid REFERENCES task(id),
    assignee_agent_id       uuid REFERENCES agent_def(id),
    created_at              timestamptz NOT NULL DEFAULT now(),
    decided_at              timestamptz,
    decided_by              text
);

CREATE TABLE vote (
    id                   uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    proposal_id          uuid NOT NULL REFERENCES proposal(id) ON DELETE CASCADE,
    voter_participant_id uuid NOT NULL REFERENCES participant(id),
    value                text NOT NULL,
    rationale            text,
    created_at           timestamptz NOT NULL DEFAULT now(),
    UNIQUE (proposal_id, voter_participant_id)
);

CREATE INDEX idx_participant_conversation ON participant(conversation_id);
CREATE INDEX idx_proposal_conversation ON proposal(conversation_id, status);
CREATE INDEX idx_task_project ON task(project_id);
