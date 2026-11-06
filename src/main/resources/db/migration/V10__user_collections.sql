CREATE TABLE collections (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id UUID NOT NULL,
    user_id UUID NOT NULL,
    name VARCHAR(120) NOT NULL,
    description VARCHAR(1000),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_collections_id_org UNIQUE (id, organization_id),
    CONSTRAINT fk_collections_member
        FOREIGN KEY (organization_id, user_id)
        REFERENCES organization_memberships (organization_id, user_id) ON DELETE CASCADE
);
CREATE INDEX idx_collections_user ON collections (organization_id, user_id, created_at DESC);

CREATE TABLE collection_items (
    organization_id UUID NOT NULL,
    collection_id UUID NOT NULL,
    property_id UUID NOT NULL,
    comment VARCHAR(1000),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (organization_id, collection_id, property_id),
    CONSTRAINT fk_collection_items_collection
        FOREIGN KEY (collection_id, organization_id)
        REFERENCES collections (id, organization_id) ON DELETE CASCADE,
    CONSTRAINT fk_collection_items_property
        FOREIGN KEY (property_id, organization_id)
        REFERENCES properties (id, organization_id) ON DELETE CASCADE
);
CREATE INDEX idx_collection_items_property ON collection_items (organization_id, property_id);
