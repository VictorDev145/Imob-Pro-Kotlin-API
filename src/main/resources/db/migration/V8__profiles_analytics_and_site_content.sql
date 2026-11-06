ALTER TABLE users ADD COLUMN phone VARCHAR(40);
ALTER TABLE users ADD COLUMN email_verified_at TIMESTAMPTZ;

CREATE TABLE email_verification_tokens (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    token_hash CHAR(64) NOT NULL UNIQUE,
    expires_at TIMESTAMPTZ NOT NULL,
    used_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_email_verification_expiry ON email_verification_tokens (expires_at)
    WHERE used_at IS NULL;

CREATE TABLE property_views (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id UUID NOT NULL,
    property_id UUID NOT NULL,
    viewer_user_id UUID,
    session_hash CHAR(64),
    source VARCHAR(24) NOT NULL DEFAULT 'DIRECT',
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT fk_property_views_property
        FOREIGN KEY (property_id, organization_id)
        REFERENCES properties (id, organization_id) ON DELETE CASCADE,
    CONSTRAINT fk_property_views_viewer
        FOREIGN KEY (organization_id, viewer_user_id)
        REFERENCES organization_memberships (organization_id, user_id),
    CONSTRAINT ck_property_views_source CHECK (
        source IN ('DIRECT', 'SEARCH', 'SOCIAL', 'PORTAL', 'EMAIL', 'OTHER')
    )
);
CREATE INDEX idx_property_views_org_created ON property_views (organization_id, created_at DESC);
CREATE INDEX idx_property_views_property_created ON property_views (organization_id, property_id, created_at DESC);

CREATE TABLE site_pages (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id UUID NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    slug VARCHAR(120) NOT NULL,
    title VARCHAR(180) NOT NULL,
    content TEXT NOT NULL DEFAULT '',
    published BOOLEAN NOT NULL DEFAULT FALSE,
    updated_by UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_site_pages_org_slug UNIQUE (organization_id, slug),
    CONSTRAINT fk_site_pages_editor
        FOREIGN KEY (organization_id, updated_by)
        REFERENCES organization_memberships (organization_id, user_id)
);
CREATE INDEX idx_site_pages_public ON site_pages (organization_id, published, slug);
