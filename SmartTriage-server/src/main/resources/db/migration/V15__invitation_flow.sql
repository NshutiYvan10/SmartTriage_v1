-- =====================================================================
-- V15: Email Invitation Flow — account_status + invitation_tokens
-- =====================================================================

-- 1. Add account_status column to users table
ALTER TABLE users ADD COLUMN account_status VARCHAR(25) NOT NULL DEFAULT 'ACTIVE';

-- Index for filtering by status
CREATE INDEX idx_user_account_status ON users (account_status);

-- 2. Create invitation_tokens table
CREATE TABLE invitation_tokens (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id         UUID         NOT NULL REFERENCES users(id),
    token           VARCHAR(255) NOT NULL UNIQUE,
    expires_at      TIMESTAMP WITH TIME ZONE NOT NULL,
    used_at         TIMESTAMP WITH TIME ZONE,
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    created_by      VARCHAR(255),
    last_modified_by VARCHAR(255),
    is_active       BOOLEAN NOT NULL DEFAULT TRUE,
    version         BIGINT  NOT NULL DEFAULT 0
);

CREATE INDEX idx_invitation_token     ON invitation_tokens (token);
CREATE INDEX idx_invitation_user_id   ON invitation_tokens (user_id);
CREATE INDEX idx_invitation_expires   ON invitation_tokens (expires_at);
CREATE INDEX idx_invitation_active    ON invitation_tokens (is_active);
