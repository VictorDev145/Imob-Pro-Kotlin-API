ALTER TABLE leads
    ADD CONSTRAINT uq_leads_id_organization UNIQUE (id, organization_id);

CREATE TABLE property_images (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id UUID NOT NULL REFERENCES organizations(id) ON DELETE RESTRICT,
    property_id UUID NOT NULL,
    storage_key VARCHAR(512) NOT NULL,
    original_filename VARCHAR(255) NOT NULL,
    content_type VARCHAR(100) NOT NULL,
    size_bytes BIGINT NOT NULL,
    position INTEGER NOT NULL DEFAULT 0,
    alt_text VARCHAR(255),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT fk_property_images_tenant
        FOREIGN KEY (property_id, organization_id)
        REFERENCES properties (id, organization_id) ON DELETE CASCADE,
    CONSTRAINT uq_property_images_storage_key UNIQUE (organization_id, storage_key),
    CONSTRAINT ck_property_images_size CHECK (size_bytes > 0),
    CONSTRAINT ck_property_images_position CHECK (position >= 0)
);
CREATE INDEX idx_property_images_order ON property_images (organization_id, property_id, position);

CREATE TABLE listings (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id UUID NOT NULL REFERENCES organizations(id) ON DELETE RESTRICT,
    property_id UUID NOT NULL,
    title VARCHAR(180) NOT NULL,
    description TEXT,
    channel VARCHAR(24) NOT NULL DEFAULT 'WEBSITE',
    status VARCHAR(24) NOT NULL DEFAULT 'DRAFT',
    public_slug VARCHAR(200),
    published_at TIMESTAMPTZ,
    expires_at TIMESTAMPTZ,
    created_by UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT fk_listings_property_tenant
        FOREIGN KEY (property_id, organization_id)
        REFERENCES properties (id, organization_id) ON DELETE CASCADE,
    CONSTRAINT fk_listings_creator_membership
        FOREIGN KEY (organization_id, created_by)
        REFERENCES organization_memberships (organization_id, user_id),
    CONSTRAINT ck_listings_channel
        CHECK (channel IN ('WEBSITE', 'PORTAL', 'SOCIAL', 'INTERNAL')),
    CONSTRAINT ck_listings_status
        CHECK (status IN ('DRAFT', 'PUBLISHED', 'PAUSED', 'EXPIRED', 'ARCHIVED')),
    CONSTRAINT uq_listings_slug UNIQUE (organization_id, public_slug)
);
CREATE INDEX idx_listings_org_status ON listings (organization_id, status, updated_at DESC);

CREATE TABLE tasks (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id UUID NOT NULL REFERENCES organizations(id) ON DELETE RESTRICT,
    title VARCHAR(180) NOT NULL,
    description TEXT,
    status VARCHAR(20) NOT NULL DEFAULT 'OPEN',
    priority VARCHAR(12) NOT NULL DEFAULT 'NORMAL',
    due_at TIMESTAMPTZ,
    assigned_to UUID,
    lead_id UUID,
    property_id UUID,
    created_by UUID NOT NULL,
    completed_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT fk_tasks_assignee
        FOREIGN KEY (organization_id, assigned_to)
        REFERENCES organization_memberships (organization_id, user_id),
    CONSTRAINT fk_tasks_creator
        FOREIGN KEY (organization_id, created_by)
        REFERENCES organization_memberships (organization_id, user_id),
    CONSTRAINT fk_tasks_lead
        FOREIGN KEY (lead_id, organization_id)
        REFERENCES leads (id, organization_id),
    CONSTRAINT fk_tasks_property
        FOREIGN KEY (property_id, organization_id)
        REFERENCES properties (id, organization_id),
    CONSTRAINT ck_tasks_status CHECK (status IN ('OPEN', 'IN_PROGRESS', 'DONE', 'CANCELLED')),
    CONSTRAINT ck_tasks_priority CHECK (priority IN ('LOW', 'NORMAL', 'HIGH', 'URGENT'))
);
CREATE INDEX idx_tasks_org_assignee_due ON tasks (organization_id, assigned_to, status, due_at);
CREATE INDEX idx_tasks_org_due ON tasks (organization_id, status, due_at);

CREATE TABLE visits (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id UUID NOT NULL REFERENCES organizations(id) ON DELETE RESTRICT,
    property_id UUID NOT NULL,
    lead_id UUID,
    assigned_to UUID,
    scheduled_at TIMESTAMPTZ NOT NULL,
    duration_minutes INTEGER NOT NULL DEFAULT 60,
    status VARCHAR(20) NOT NULL DEFAULT 'SCHEDULED',
    notes TEXT,
    created_by UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT fk_visits_property
        FOREIGN KEY (property_id, organization_id)
        REFERENCES properties (id, organization_id),
    CONSTRAINT fk_visits_lead
        FOREIGN KEY (lead_id, organization_id)
        REFERENCES leads (id, organization_id),
    CONSTRAINT fk_visits_assignee
        FOREIGN KEY (organization_id, assigned_to)
        REFERENCES organization_memberships (organization_id, user_id),
    CONSTRAINT fk_visits_creator
        FOREIGN KEY (organization_id, created_by)
        REFERENCES organization_memberships (organization_id, user_id),
    CONSTRAINT ck_visits_duration CHECK (duration_minutes BETWEEN 15 AND 480),
    CONSTRAINT ck_visits_status CHECK (status IN ('SCHEDULED', 'CONFIRMED', 'COMPLETED', 'CANCELLED', 'NO_SHOW'))
);
CREATE INDEX idx_visits_org_schedule ON visits (organization_id, scheduled_at, status);
CREATE INDEX idx_visits_org_assignee ON visits (organization_id, assigned_to, scheduled_at);

CREATE TABLE notifications (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id UUID NOT NULL,
    user_id UUID NOT NULL,
    type VARCHAR(40) NOT NULL,
    title VARCHAR(180) NOT NULL,
    body TEXT,
    resource_type VARCHAR(40),
    resource_id UUID,
    read_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT fk_notifications_membership
        FOREIGN KEY (organization_id, user_id)
        REFERENCES organization_memberships (organization_id, user_id) ON DELETE CASCADE
);
CREATE INDEX idx_notifications_inbox ON notifications (organization_id, user_id, read_at, created_at DESC);

CREATE TABLE activity_log (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id UUID NOT NULL REFERENCES organizations(id) ON DELETE RESTRICT,
    actor_user_id UUID,
    action VARCHAR(80) NOT NULL,
    resource_type VARCHAR(60) NOT NULL,
    resource_id UUID,
    metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT fk_activity_actor
        FOREIGN KEY (organization_id, actor_user_id)
        REFERENCES organization_memberships (organization_id, user_id)
);
CREATE INDEX idx_activity_org_created ON activity_log (organization_id, created_at DESC);
