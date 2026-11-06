CREATE TABLE automation_events (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id UUID NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    trigger_key VARCHAR(80) NOT NULL,
    resource_type VARCHAR(60) NOT NULL,
    resource_id UUID,
    actor_user_id UUID NOT NULL,
    payload JSONB NOT NULL DEFAULT '{}'::jsonb,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    attempts INTEGER NOT NULL DEFAULT 0,
    available_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    processed_at TIMESTAMPTZ,
    last_error VARCHAR(1000),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT fk_automation_event_actor
        FOREIGN KEY (organization_id, actor_user_id)
        REFERENCES organization_memberships (organization_id, user_id),
    CONSTRAINT ck_automation_event_status
        CHECK (status IN ('PENDING', 'PROCESSING', 'PROCESSED', 'FAILED')),
    CONSTRAINT ck_automation_event_attempts CHECK (attempts >= 0)
);
CREATE INDEX idx_automation_events_pending
    ON automation_events (available_at, created_at)
    WHERE status IN ('PENDING', 'FAILED');
