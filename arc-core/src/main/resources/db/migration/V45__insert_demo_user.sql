-- =====================================================
-- V45: Demo user for local development & testing
-- =====================================================
-- Pre-configured demo account so the web UI can be used
-- immediately without manual registration.
-- Email: demo@arc-reactor.io / Password: demo1234

INSERT INTO users (id, email, name, password_hash, role, tenant_id, created_at)
VALUES (
    '00000000-0000-0000-0000-000000000099',
    'demo@arc-reactor.io',
    'Demo User',
    '$2b$12$Tv1l/fjW966rKg7bd0zahe2sxJIpByGpBhy6.haKINdyNVsGQ1URi',
    'USER',
    'default',
    NOW()
)
ON CONFLICT (email) DO NOTHING;
