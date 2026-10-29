-- =============================================================================
-- V6: Google OAuth2 Provider Columns & Nullable Password Hash (Phase 7)
-- =============================================================================

ALTER TABLE users
    MODIFY COLUMN password_hash VARCHAR(255) NULL,
    ADD COLUMN auth_provider VARCHAR(20) NOT NULL DEFAULT 'LOCAL' AFTER role,
    ADD COLUMN google_sub VARCHAR(100) NULL AFTER auth_provider,
    ADD CONSTRAINT uk_users_google_sub UNIQUE (google_sub);
