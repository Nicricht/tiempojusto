-- TiempoJusto V1.3 - OAuth2/OIDC identity mapping
-- Source: Documento Maestro V1.7 authentication requirement (OAuth2/JWT/refresh).
-- No passwords, access tokens, refresh tokens, raw KYC documents or biometrics are stored here.

BEGIN;

CREATE TABLE IF NOT EXISTS iam.oauth_identity (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id uuid NOT NULL REFERENCES iam.app_user(id) ON DELETE CASCADE,
    issuer varchar(512) NOT NULL,
    subject varchar(255) NOT NULL,
    linked_at timestamptz NOT NULL DEFAULT now(),
    last_authenticated_at timestamptz,
    revoked_at timestamptz,
    CONSTRAINT oauth_identity_issuer_ck CHECK (length(trim(issuer)) > 0),
    CONSTRAINT oauth_identity_subject_ck CHECK (length(trim(subject)) > 0),
    CONSTRAINT oauth_identity_auth_time_ck CHECK (
        last_authenticated_at IS NULL OR last_authenticated_at >= linked_at
    ),
    CONSTRAINT oauth_identity_revoke_time_ck CHECK (
        revoked_at IS NULL OR revoked_at >= linked_at
    ),
    UNIQUE (issuer, subject)
);

CREATE INDEX IF NOT EXISTS oauth_identity_user_idx
ON iam.oauth_identity(user_id, linked_at DESC);

CREATE INDEX IF NOT EXISTS oauth_identity_active_lookup_idx
ON iam.oauth_identity(issuer, subject)
WHERE revoked_at IS NULL;

COMMENT ON TABLE iam.oauth_identity IS
'Provider-neutral OAuth2/OIDC identity binding. Store only issuer+subject linkage; never store raw access/refresh tokens here.';

COMMIT;
