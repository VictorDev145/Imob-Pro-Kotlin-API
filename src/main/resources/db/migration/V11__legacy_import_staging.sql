CREATE TABLE legacy_import_batches (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    source_database_sha256 CHAR(64) NOT NULL,
    manifest_sha256 CHAR(64) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'STAGING',
    source_table_count INTEGER NOT NULL,
    imported_row_count BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    completed_at TIMESTAMPTZ,
    CONSTRAINT ck_legacy_import_batch_status CHECK (status IN ('STAGING', 'RECONCILED', 'FAILED'))
);
CREATE TABLE legacy_import_rows (
    batch_id UUID NOT NULL REFERENCES legacy_import_batches(id) ON DELETE CASCADE,
    source_table VARCHAR(80) NOT NULL,
    legacy_id TEXT NOT NULL,
    row_data JSONB NOT NULL,
    row_sha256 CHAR(64) NOT NULL,
    imported_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (batch_id, source_table, legacy_id)
);
CREATE INDEX idx_legacy_import_rows_table ON legacy_import_rows(batch_id, source_table);
