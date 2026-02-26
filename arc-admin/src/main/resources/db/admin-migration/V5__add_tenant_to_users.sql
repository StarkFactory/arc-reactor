-- Add tenant_id to existing users table
-- tenant_id = NULL means Platform Admin
ALTER TABLE users ADD COLUMN IF NOT EXISTS tenant_id VARCHAR(36) REFERENCES tenants(id);
CREATE INDEX IF NOT EXISTS idx_users_tenant ON users(tenant_id);
