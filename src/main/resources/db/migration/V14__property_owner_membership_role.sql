ALTER TABLE organization_memberships
    DROP CONSTRAINT ck_membership_role;

ALTER TABLE organization_memberships
    ADD CONSTRAINT ck_membership_role
    CHECK (role IN ('OWNER', 'ADMIN', 'MANAGER', 'AGENT', 'CUSTOMER', 'PROPERTY_OWNER'));
