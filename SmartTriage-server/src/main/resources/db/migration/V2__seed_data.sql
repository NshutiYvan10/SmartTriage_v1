-- =====================================================================
-- SmartTriage V2 — Seed Data
-- Default super admin and system hospital for bootstrapping
-- =====================================================================

-- System hospital (for super admin association)
INSERT INTO hospitals (id, name, hospital_code, address, city, country, tier, is_active, created_at, created_by)
VALUES (
    'a0000000-0000-0000-0000-000000000001',
    'SmartTriage Central',
    'STC-001',
    'System Administration',
    'Central',
    'ZAF',
    'System',
    TRUE,
    NOW(),
    'SYSTEM'
);

-- Super admin user
-- Password: SmartTriage@2026 (BCrypt hash with strength 12)
-- IMPORTANT: Change this password immediately after first login in production
INSERT INTO users (id, first_name, last_name, email, password_hash, role, hospital_id, department, is_active, created_at, created_by)
VALUES (
    'b0000000-0000-0000-0000-000000000001',
    'System',
    'Administrator',
    'admin@smarttriage.com',
    '$2a$12$LJ3m4sFQm/J.HGKcY7DqUeVB0HhQJZGLxL.r/jK1YGNn6F0V6HT4a',
    'SUPER_ADMIN',
    'a0000000-0000-0000-0000-000000000001',
    'System Administration',
    TRUE,
    NOW(),
    'SYSTEM'
);
