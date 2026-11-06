CREATE TABLE auth_sessions (
 id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
 user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
 organization_id UUID NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
 access_token_hash CHAR(64) NOT NULL UNIQUE,
 refresh_token_hash CHAR(64) NOT NULL UNIQUE,
 access_expires_at TIMESTAMPTZ NOT NULL,
 refresh_expires_at TIMESTAMPTZ NOT NULL,
 revoked_at TIMESTAMPTZ,
 created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_auth_sessions_user ON auth_sessions(user_id);
CREATE INDEX idx_auth_sessions_access_expiry ON auth_sessions(access_expires_at) WHERE revoked_at IS NULL;
CREATE INDEX idx_auth_sessions_refresh_expiry ON auth_sessions(refresh_expires_at) WHERE revoked_at IS NULL;
