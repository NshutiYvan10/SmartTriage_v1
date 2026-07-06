-- V108: Origin capture on the audit log (incident forensics).
--
-- "Who did what FROM WHERE" — the audit trail carried actor + action + patient
-- (V107) but no request origin, so a review could not distinguish the ward
-- workstation from an off-site session or a hijacked token. The interceptor now
-- stamps the client IP (X-Forwarded-For aware, first hop) and the User-Agent on
-- every audited row; the custom producers (RFID / cross-hospital reads) inherit
-- them from the same request-scoped context for free.

ALTER TABLE audit_logs ADD COLUMN source_ip  VARCHAR(45);   -- IPv6 max textual length
ALTER TABLE audit_logs ADD COLUMN user_agent VARCHAR(256);
