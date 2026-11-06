ALTER TABLE properties
    ADD CONSTRAINT uq_properties_id_organization UNIQUE (id, organization_id);

CREATE TABLE leads (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id UUID NOT NULL REFERENCES organizations(id) ON DELETE RESTRICT,
    created_by UUID NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    assigned_to UUID,
    property_id UUID,
    full_name VARCHAR(160) NOT NULL,
    email VARCHAR(254),
    phone VARCHAR(40),
    source VARCHAR(24) NOT NULL DEFAULT 'OTHER',
    status VARCHAR(24) NOT NULL DEFAULT 'NEW',
    message TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT fk_leads_assigned_membership
        FOREIGN KEY (organization_id, assigned_to)
        REFERENCES organization_memberships (organization_id, user_id),
    CONSTRAINT fk_leads_property_tenant
        FOREIGN KEY (property_id, organization_id)
        REFERENCES properties (id, organization_id),
    CONSTRAINT ck_leads_source
        CHECK (source IN ('WEBSITE', 'PHONE', 'WHATSAPP', 'WALK_IN', 'REFERRAL', 'OTHER')),
    CONSTRAINT ck_leads_status
        CHECK (status IN ('NEW', 'CONTACTED', 'QUALIFIED', 'CONVERTED', 'LOST'))
);

CREATE INDEX idx_leads_org_status_created ON leads (organization_id, status, created_at DESC);
CREATE INDEX idx_leads_org_assigned ON leads (organization_id, assigned_to, status);
CREATE INDEX idx_leads_org_email ON leads (organization_id, email);
