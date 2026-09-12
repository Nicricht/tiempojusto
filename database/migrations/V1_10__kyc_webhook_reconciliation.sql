BEGIN;

-- TiempoJusto V1.10 - durable KYC webhook inbox/reconciliation state.
-- Raw provider payloads are never persisted. The existing SHA-256 envelope stays
-- the idempotency/audit boundary while processing state supports retries and
-- out-of-order provider delivery.

ALTER TABLE iam.identity_provider_event
    ADD COLUMN processing_status varchar(32) NOT NULL DEFAULT 'RECEIVED',
    ADD COLUMN attempt_count integer NOT NULL DEFAULT 0,
    ADD COLUMN next_attempt_at timestamptz NOT NULL DEFAULT now(),
    ADD COLUMN last_error_code varchar(100),
    ADD COLUMN last_error_detail varchar(500);

ALTER TABLE iam.identity_provider_event
    ADD CONSTRAINT identity_provider_event_processing_status_ck CHECK (
        processing_status IN (
            'RECEIVED',
            'PROCESSING',
            'PENDING_LOCAL_SESSION',
            'PENDING_PROVIDER',
            'APPLIED',
            'CONFLICT',
            'FAILED'
        )
    ),
    ADD CONSTRAINT identity_provider_event_attempt_count_ck CHECK (attempt_count >= 0);

UPDATE iam.identity_provider_event
   SET processing_status = CASE WHEN applied_at IS NULL THEN 'RECEIVED' ELSE 'APPLIED' END,
       next_attempt_at = coalesce(received_at, now());

CREATE INDEX identity_provider_event_pending_idx
ON iam.identity_provider_event(processing_status, next_attempt_at, received_at)
WHERE processing_status IN ('RECEIVED','PENDING_LOCAL_SESSION','PENDING_PROVIDER');

COMMENT ON COLUMN iam.identity_provider_event.processing_status IS
'Durable provider webhook processing state. Raw provider payload remains excluded from persistence.';
COMMENT ON COLUMN iam.identity_provider_event.attempt_count IS
'Number of internal reconciliation attempts for this signed webhook envelope.';
COMMENT ON COLUMN iam.identity_provider_event.next_attempt_at IS
'Internal retry schedule for provider polling/local-session convergence.';

COMMIT;
