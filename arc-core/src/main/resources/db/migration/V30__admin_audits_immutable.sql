-- Enforce append-only semantics for compliance-sensitive admin audit logs.
-- Blocks UPDATE/DELETE so records cannot be modified after insertion.

CREATE OR REPLACE FUNCTION prevent_admin_audits_modification()
RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'admin_audits records are immutable';
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS admin_audits_immutable ON admin_audits;

CREATE TRIGGER admin_audits_immutable
    BEFORE UPDATE OR DELETE ON admin_audits
    FOR EACH ROW
    EXECUTE FUNCTION prevent_admin_audits_modification();
