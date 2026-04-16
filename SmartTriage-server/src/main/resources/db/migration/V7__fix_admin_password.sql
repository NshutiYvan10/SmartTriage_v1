-- =====================================================================
-- SmartTriage V7 — Fix super admin password hash
-- The BCrypt hash in V2 was incorrect. This sets the correct one.
-- Password: SmartTriage@2026 (BCrypt strength 12)
-- =====================================================================

UPDATE users
SET password_hash = '$2a$12$IgzO7/Z15Bs4X1iR6faKbeMYSqUTYcd6KAjSdyy5ws7AJVTal6oaC'
WHERE email = 'admin@smarttriage.com';
