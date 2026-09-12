BEGIN;

-- Provider-neutral KYC bindings. Raw identity documents, selfie/video media,
-- biometric templates and complete provider webhook payloads must remain outside
-- TiempoJusto persistence.
CREATE TABLE iam.identity_provider_session (
    verification_id uuid PRIMARY KEY REFERENCES iam.identity_verification(id) ON DELETE CASCADE,
    provider_code varchar(50) NOT NULL,
    provider_reference varchar(160) NOT NULL UNIQUE,
    normalized_status platform.verification_status NOT NULL DEFAULT 'PENDING',
    last_provider_event_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT identity_provider_session_provider_ck CHECK (length(trim(provider_code)) > 0),
    CONSTRAINT identity_provider_session_reference_ck CHECK (length(trim(provider_reference)) > 0)
);

COMMENT ON TABLE iam.identity_provider_session IS
'Provider KYC session references and normalized state only. Never store provider session URL/token, raw documents, raw biometrics or selfie/video payloads.';

CREATE UNIQUE INDEX identity_one_open_verification_per_provider_uidx
ON iam.identity_verification(user_id, provider_code)
WHERE status IN ('PENDING','REVIEW');

CREATE TABLE iam.identity_provider_event (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    provider_code varchar(50) NOT NULL,
    provider_reference varchar(160) NOT NULL,
    payload_sha256 char(64) NOT NULL,
    event_type varchar(40) NOT NULL,
    normalized_status platform.verification_status,
    received_at timestamptz NOT NULL DEFAULT now(),
    applied_at timestamptz,
    CONSTRAINT identity_provider_event_hash_ck CHECK (payload_sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT identity_provider_event_apply_ck CHECK (applied_at IS NULL OR applied_at >= received_at),
    UNIQUE (provider_code, payload_sha256)
);

CREATE INDEX identity_provider_event_reference_idx
ON iam.identity_provider_event(provider_code, provider_reference, received_at DESC);

COMMENT ON TABLE iam.identity_provider_event IS
'Idempotency/audit envelope for KYC webhooks. Stores only SHA-256 and normalized metadata; never the raw provider webhook payload.';

COMMIT;
