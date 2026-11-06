CREATE TABLE properties (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id UUID NOT NULL REFERENCES organizations(id) ON DELETE RESTRICT,
    created_by UUID NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    reference_code VARCHAR(24) NOT NULL,
    property_type VARCHAR(24) NOT NULL,
    purpose VARCHAR(16) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'DRAFT',
    title VARCHAR(180) NOT NULL,
    description TEXT,
    street VARCHAR(180),
    street_number VARCHAR(30),
    complement VARCHAR(100),
    neighborhood VARCHAR(100),
    city VARCHAR(100) NOT NULL,
    state CHAR(2) NOT NULL,
    postal_code VARCHAR(12),
    bedrooms SMALLINT,
    bathrooms SMALLINT,
    parking_spaces SMALLINT,
    built_area NUMERIC(12,2),
    land_area NUMERIC(12,2),
    sale_price NUMERIC(14,2),
    rent_price NUMERIC(14,2),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    archived_at TIMESTAMPTZ,
    CONSTRAINT uq_properties_org_reference UNIQUE (organization_id, reference_code),
    CONSTRAINT ck_properties_type CHECK (property_type IN ('HOUSE', 'APARTMENT', 'LAND', 'COMMERCIAL', 'RURAL', 'OTHER')),
    CONSTRAINT ck_properties_purpose CHECK (purpose IN ('SALE', 'RENT', 'BOTH')),
    CONSTRAINT ck_properties_status CHECK (status IN ('DRAFT', 'ACTIVE', 'ARCHIVED')),
    CONSTRAINT ck_properties_bedrooms CHECK (bedrooms IS NULL OR bedrooms >= 0),
    CONSTRAINT ck_properties_bathrooms CHECK (bathrooms IS NULL OR bathrooms >= 0),
    CONSTRAINT ck_properties_parking CHECK (parking_spaces IS NULL OR parking_spaces >= 0),
    CONSTRAINT ck_properties_built_area CHECK (built_area IS NULL OR built_area > 0),
    CONSTRAINT ck_properties_land_area CHECK (land_area IS NULL OR land_area > 0),
    CONSTRAINT ck_properties_sale_price CHECK (sale_price IS NULL OR sale_price >= 0),
    CONSTRAINT ck_properties_rent_price CHECK (rent_price IS NULL OR rent_price >= 0)
);

CREATE INDEX idx_properties_org_status_created ON properties (organization_id, status, created_at DESC);
CREATE INDEX idx_properties_org_city ON properties (organization_id, city);
CREATE INDEX idx_properties_org_type ON properties (organization_id, property_type);
CREATE INDEX idx_properties_org_reference ON properties (organization_id, reference_code);
