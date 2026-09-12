-- TiempoJusto V1.9 - provider webhook inbox + reconciliation evidence
-- Stores only normalized provider identifiers/state. Never store PAN, CVV,
-- card tokens, access tokens, webhook secrets or raw provider payloads.

CREATE TABLE finance.provider_webhook_event (
    id uuid PRIMARY KEY,
    provider_code varchar(40) NOT NULL,
    provider_event_id varchar(160) NOT NULL,
    provider_resource_id varchar(160) NOT NULL,
    resource_type varchar(80) NOT NULL,
    action varchar(120),
    request_id varchar(160),
    live_mode boolean,
    signature_ts varchar(40) NOT NULL,
    payload_sha256 char(64) NOT NULL,
    processing_status varchar(32) NOT NULL DEFAULT 'RECEIVED'
        CHECK (processing_status IN (
            'RECEIVED','PROCESSING','IGNORED','MATCHED','MISMATCH','OBSERVED',
            'UNBOUND','PENDING_LOCAL_COMMIT','FAILED'
        )),
    attempt_count integer NOT NULL DEFAULT 0 CHECK (attempt_count >= 0),
    next_attempt_at timestamptz NOT NULL DEFAULT now(),
    received_at timestamptz NOT NULL DEFAULT now(),
    processed_at timestamptz,
    last_error_code varchar(100),
    last_error_detail varchar(500),
    UNIQUE (provider_code, provider_event_id)
);

CREATE TABLE finance.provider_reconciliation (
    id bigserial PRIMARY KEY,
    webhook_event_id uuid NOT NULL UNIQUE
        REFERENCES finance.provider_webhook_event(id) ON DELETE RESTRICT,
    provider_code varchar(40) NOT NULL,
    provider_payment_id varchar(160) NOT NULL,
    internal_reservation_id uuid
        REFERENCES finance.provider_reservation_binding(internal_id) ON DELETE RESTRICT,
    internal_capture_id uuid
        REFERENCES finance.provider_capture_binding(internal_id) ON DELETE RESTRICT,
    provider_status varchar(80) NOT NULL,
    provider_amount_clp bigint CHECK (provider_amount_clp IS NULL OR provider_amount_clp >= 0),
    provider_refunded_clp bigint CHECK (provider_refunded_clp IS NULL OR provider_refunded_clp >= 0),
    local_authorized_clp bigint CHECK (local_authorized_clp IS NULL OR local_authorized_clp >= 0),
    local_captured_clp bigint CHECK (local_captured_clp IS NULL OR local_captured_clp >= 0),
    local_refunded_clp bigint CHECK (local_refunded_clp IS NULL OR local_refunded_clp >= 0),
    result varchar(32) NOT NULL
        CHECK (result IN ('MATCHED','MISMATCH','UNBOUND','PENDING_LOCAL_COMMIT','OBSERVED')),
    reason varchar(500) NOT NULL,
    checked_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX provider_webhook_pending_idx
    ON finance.provider_webhook_event (processing_status, next_attempt_at, received_at);
CREATE INDEX provider_webhook_resource_idx
    ON finance.provider_webhook_event (provider_code, provider_resource_id, received_at DESC);
CREATE INDEX provider_reconciliation_payment_idx
    ON finance.provider_reconciliation (provider_code, provider_payment_id, checked_at DESC);

COMMENT ON TABLE finance.provider_webhook_event IS
'Authenticated payment-provider webhook inbox. Payload is represented only by SHA-256; secrets and raw payment data are never persisted.';
COMMENT ON TABLE finance.provider_reconciliation IS
'Provider-vs-TiempoJusto normalized monetary reconciliation evidence. It never mutates ledger balances.';