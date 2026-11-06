ALTER TABLE listings DROP CONSTRAINT ck_listings_status;
ALTER TABLE listings ADD CONSTRAINT ck_listings_status
    CHECK (status IN ('DRAFT', 'PENDING', 'PUBLISHED', 'PAUSED', 'SOLD', 'RENTED', 'EXPIRED', 'ARCHIVED'));
