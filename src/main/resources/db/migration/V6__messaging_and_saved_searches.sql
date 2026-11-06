CREATE TABLE conversations (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id UUID NOT NULL REFERENCES organizations(id) ON DELETE RESTRICT,
    lead_id UUID,
    subject VARCHAR(180) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'OPEN',
    created_by UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_conversations_id_org UNIQUE (id, organization_id),
    CONSTRAINT fk_conversations_lead
        FOREIGN KEY (lead_id, organization_id)
        REFERENCES leads (id, organization_id),
    CONSTRAINT fk_conversations_creator
        FOREIGN KEY (organization_id, created_by)
        REFERENCES organization_memberships (organization_id, user_id),
    CONSTRAINT ck_conversations_status CHECK (status IN ('OPEN', 'CLOSED', 'ARCHIVED'))
);
CREATE INDEX idx_conversations_org_updated ON conversations (organization_id, updated_at DESC);

CREATE TABLE conversation_members (
    organization_id UUID NOT NULL,
    conversation_id UUID NOT NULL,
    user_id UUID NOT NULL,
    joined_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    last_read_at TIMESTAMPTZ,
    PRIMARY KEY (organization_id, conversation_id, user_id),
    CONSTRAINT fk_conversation_members_conversation
        FOREIGN KEY (conversation_id, organization_id)
        REFERENCES conversations (id, organization_id) ON DELETE CASCADE,
    CONSTRAINT fk_conversation_members_membership
        FOREIGN KEY (organization_id, user_id)
        REFERENCES organization_memberships (organization_id, user_id) ON DELETE CASCADE
);

CREATE TABLE messages (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id UUID NOT NULL,
    conversation_id UUID NOT NULL,
    sender_user_id UUID NOT NULL,
    body TEXT NOT NULL,
    edited_at TIMESTAMPTZ,
    deleted_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT fk_messages_conversation
        FOREIGN KEY (conversation_id, organization_id)
        REFERENCES conversations (id, organization_id) ON DELETE CASCADE,
    CONSTRAINT fk_messages_sender_membership
        FOREIGN KEY (organization_id, sender_user_id)
        REFERENCES organization_memberships (organization_id, user_id),
    CONSTRAINT ck_messages_body CHECK (length(trim(body)) BETWEEN 1 AND 20000)
);
CREATE INDEX idx_messages_conversation_created ON messages (organization_id, conversation_id, created_at DESC);

CREATE TABLE favorites (
    organization_id UUID NOT NULL,
    user_id UUID NOT NULL,
    property_id UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (organization_id, user_id, property_id),
    CONSTRAINT fk_favorites_membership
        FOREIGN KEY (organization_id, user_id)
        REFERENCES organization_memberships (organization_id, user_id) ON DELETE CASCADE,
    CONSTRAINT fk_favorites_property
        FOREIGN KEY (property_id, organization_id)
        REFERENCES properties (id, organization_id) ON DELETE CASCADE
);
CREATE INDEX idx_favorites_user ON favorites (organization_id, user_id, created_at DESC);

CREATE TABLE saved_searches (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id UUID NOT NULL,
    user_id UUID NOT NULL,
    name VARCHAR(120) NOT NULL,
    criteria JSONB NOT NULL DEFAULT '{}'::jsonb,
    alerts_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    last_alerted_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT fk_saved_searches_membership
        FOREIGN KEY (organization_id, user_id)
        REFERENCES organization_memberships (organization_id, user_id) ON DELETE CASCADE
);
CREATE INDEX idx_saved_searches_user ON saved_searches (organization_id, user_id, updated_at DESC);
