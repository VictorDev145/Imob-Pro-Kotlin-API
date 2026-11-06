-- Stable numeric aliases keep the legacy browser contract working while the
-- domain model uses UUIDs. The mapping is global, but aliases are only exposed
-- for contacts returned from the authenticated tenant-scoped API.
CREATE TABLE legacy_user_id_map (
    legacy_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id UUID NOT NULL UNIQUE REFERENCES users(id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_legacy_user_id_map_user ON legacy_user_id_map(user_id);
