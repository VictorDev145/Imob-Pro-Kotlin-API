CREATE TABLE password_reset_tokens (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    token_hash CHAR(64) NOT NULL UNIQUE,
    expires_at TIMESTAMPTZ NOT NULL,
    used_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_password_reset_user_created ON password_reset_tokens (user_id, created_at DESC);
CREATE INDEX idx_password_reset_expiry ON password_reset_tokens (expires_at) WHERE used_at IS NULL;

CREATE TABLE organization_invitations (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id UUID NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    email VARCHAR(254) NOT NULL,
    role VARCHAR(32) NOT NULL,
    token_hash CHAR(64) NOT NULL UNIQUE,
    invited_by UUID NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    accepted_at TIMESTAMPTZ,
    revoked_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT fk_invitation_creator
        FOREIGN KEY (organization_id, invited_by)
        REFERENCES organization_memberships (organization_id, user_id),
    CONSTRAINT ck_invitation_role CHECK (role IN ('ADMIN', 'MANAGER', 'AGENT', 'CUSTOMER'))
);
CREATE INDEX idx_invitations_org_email ON organization_invitations (organization_id, email);
CREATE INDEX idx_invitations_expiry ON organization_invitations (expires_at)
    WHERE accepted_at IS NULL AND revoked_at IS NULL;

CREATE TABLE automation_rules (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id UUID NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    name VARCHAR(160) NOT NULL,
    trigger_key VARCHAR(80) NOT NULL,
    action_key VARCHAR(80) NOT NULL,
    configuration JSONB NOT NULL DEFAULT '{}'::jsonb,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_by UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT fk_automation_creator
        FOREIGN KEY (organization_id, created_by)
        REFERENCES organization_memberships (organization_id, user_id)
);
CREATE INDEX idx_automation_rules_enabled ON automation_rules (organization_id, enabled, trigger_key);

CREATE TABLE organization_settings (
    organization_id UUID NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    setting_key VARCHAR(100) NOT NULL,
    setting_value JSONB NOT NULL,
    updated_by UUID NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (organization_id, setting_key),
    CONSTRAINT fk_org_settings_updater
        FOREIGN KEY (organization_id, updated_by)
        REFERENCES organization_memberships (organization_id, user_id)
);

CREATE TABLE privacy_requests (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id UUID NOT NULL,
    user_id UUID NOT NULL,
    request_type VARCHAR(24) NOT NULL,
    status VARCHAR(24) NOT NULL DEFAULT 'OPEN',
    details TEXT,
    resolution_notes TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    resolved_at TIMESTAMPTZ,
    CONSTRAINT fk_privacy_request_member
        FOREIGN KEY (organization_id, user_id)
        REFERENCES organization_memberships (organization_id, user_id),
    CONSTRAINT ck_privacy_request_type CHECK (
        request_type IN ('ACCESS', 'CORRECTION', 'DELETION', 'PORTABILITY', 'REVOCATION', 'INFORMATION')
    ),
    CONSTRAINT ck_privacy_request_status CHECK (
        status IN ('OPEN', 'IN_PROGRESS', 'RESOLVED', 'REJECTED')
    )
);
CREATE INDEX idx_privacy_requests_org_status ON privacy_requests (organization_id, status, created_at DESC);
