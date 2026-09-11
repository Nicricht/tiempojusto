-- TiempoJusto V1.5 - Persistent session settlement and payout availability
-- Source: Documento Maestro V1.7 finance rules.
-- Session/extension revenue split is 80% HOST / 20% TiempoJusto on amount actually generated.
-- Payout remains pending for at least 60 minutes and an objective incident may hold it.
-- No CLP rounding rule is invented here: non-integral proportional amounts/splits remain pending policy.

BEGIN;

ALTER TYPE platform.ledger_tx_type ADD VALUE IF NOT EXISTS 'HOLD_RELEASE';

ALTER TABLE auction.funds_reservation
    ADD COLUMN IF NOT EXISTS captured_amount_clp bigint NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS released_amount_clp bigint NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS finalized_at timestamptz;

ALTER TABLE auction.funds_reservation
    ADD CONSTRAINT funds_final_amounts_ck CHECK (
        captured_amount_clp >= 0
        AND released_amount_clp >= 0
        AND captured_amount_clp + released_amount_clp <= amount_clp
    );

COMMENT ON COLUMN auction.funds_reservation.captured_amount_clp IS
'Amount actually captured from the reservation. Allows partial session settlement without pretending unused funds were charged.';
COMMENT ON COLUMN auction.funds_reservation.released_amount_clp IS
'Unused reserved amount released back to the payer after final settlement.';

-- NULL owner_user_id is used by PLATFORM and PROVIDER_CLEARING accounts. The base UNIQUE
-- constraint cannot deduplicate NULL values in PostgreSQL, so this closes that integrity gap.
CREATE UNIQUE INDEX IF NOT EXISTS ledger_non_user_account_uidx
ON finance.ledger_account(owner_type, account_type, currency)
WHERE owner_user_id IS NULL;

CREATE UNIQUE INDEX IF NOT EXISTS ledger_one_session_settlement_uidx
ON finance.ledger_transaction(reference_type, reference_id)
WHERE transaction_type = 'SESSION_SETTLEMENT'
  AND reference_type = 'SESSION'
  AND reference_id IS NOT NULL
  AND status <> 'REVERSED';

CREATE TABLE IF NOT EXISTS finance.session_settlement (
    session_id uuid PRIMARY KEY REFERENCES appointment.appointment_session(id) ON DELETE RESTRICT,
    appointment_id uuid NOT NULL UNIQUE REFERENCES appointment.appointment(id) ON DELETE RESTRICT,
    host_user_id uuid NOT NULL REFERENCES iam.app_user(id) ON DELETE RESTRICT,
    bidder_user_id uuid NOT NULL REFERENCES iam.app_user(id) ON DELETE RESTRICT,
    funds_reservation_id uuid NOT NULL REFERENCES auction.funds_reservation(id) ON DELETE RESTRICT,
    ledger_transaction_id uuid UNIQUE REFERENCES finance.ledger_transaction(id) ON DELETE RESTRICT,
    provider_capture_reference varchar(160),
    status varchar(40) NOT NULL CHECK (status IN ('POSTED','ZERO_BILLING','PENDING_ROUNDING_POLICY')),
    agreed_amount_clp bigint NOT NULL CHECK (agreed_amount_clp > 0),
    billable_seconds integer NOT NULL CHECK (billable_seconds >= 0),
    full_duration_seconds integer NOT NULL CHECK (full_duration_seconds > 0),
    generated_amount_clp bigint CHECK (generated_amount_clp IS NULL OR generated_amount_clp >= 0),
    platform_fee_clp bigint CHECK (platform_fee_clp IS NULL OR platform_fee_clp >= 0),
    host_net_clp bigint CHECK (host_net_clp IS NULL OR host_net_clp >= 0),
    rounding_reason varchar(80),
    created_at timestamptz NOT NULL DEFAULT now(),
    settled_at timestamptz,
    CONSTRAINT session_settlement_billable_cap_ck CHECK (billable_seconds <= full_duration_seconds),
    CONSTRAINT session_settlement_parties_ck CHECK (host_user_id <> bidder_user_id),
    CONSTRAINT session_settlement_state_ck CHECK (
        (
            status = 'POSTED'
            AND billable_seconds > 0
            AND generated_amount_clp IS NOT NULL AND generated_amount_clp > 0
            AND platform_fee_clp IS NOT NULL
            AND host_net_clp IS NOT NULL
            AND generated_amount_clp = platform_fee_clp + host_net_clp
            AND ledger_transaction_id IS NOT NULL
            AND provider_capture_reference IS NOT NULL
            AND settled_at IS NOT NULL
            AND rounding_reason IS NULL
        )
        OR
        (
            status = 'ZERO_BILLING'
            AND billable_seconds = 0
            AND generated_amount_clp = 0
            AND platform_fee_clp = 0
            AND host_net_clp = 0
            AND ledger_transaction_id IS NULL
            AND provider_capture_reference IS NULL
            AND settled_at IS NOT NULL
            AND rounding_reason IS NULL
        )
        OR
        (
            status = 'PENDING_ROUNDING_POLICY'
            AND billable_seconds > 0
            AND ledger_transaction_id IS NULL
            AND provider_capture_reference IS NULL
            AND platform_fee_clp IS NULL
            AND host_net_clp IS NULL
            AND settled_at IS NULL
            AND rounding_reason IS NOT NULL
        )
    )
);

COMMENT ON TABLE finance.session_settlement IS
'Persistent settlement evidence for a finished session. PENDING_ROUNDING_POLICY deliberately blocks money movement when V1.7 does not define an exact CLP rounding rule.';

CREATE UNIQUE INDEX IF NOT EXISTS payout_source_transaction_uidx
ON finance.payout(source_transaction_id);

ALTER TABLE finance.payout
    ADD COLUMN IF NOT EXISTS available_at timestamptz,
    ADD COLUMN IF NOT EXISTS availability_transaction_id uuid REFERENCES finance.ledger_transaction(id) ON DELETE RESTRICT;

CREATE TABLE IF NOT EXISTS finance.payout_hold (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    payout_id uuid NOT NULL REFERENCES finance.payout(id) ON DELETE CASCADE,
    safety_case_id uuid REFERENCES safety.safety_case(id) ON DELETE SET NULL,
    reason_code varchar(80) NOT NULL,
    evidence_ref text NOT NULL,
    status varchar(16) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','RELEASED')),
    created_at timestamptz NOT NULL DEFAULT now(),
    released_at timestamptz,
    CONSTRAINT payout_hold_evidence_ck CHECK (length(trim(evidence_ref)) > 0),
    CONSTRAINT payout_hold_release_time_ck CHECK (
        (status = 'ACTIVE' AND released_at IS NULL)
        OR (status = 'RELEASED' AND released_at IS NOT NULL AND released_at >= created_at)
    )
);

CREATE UNIQUE INDEX IF NOT EXISTS one_active_payout_hold_uidx
ON finance.payout_hold(payout_id)
WHERE status = 'ACTIVE';

COMMENT ON TABLE finance.payout_hold IS
'Explicit audited hold for an objective incident. A mere report does not automatically become an objective payout hold.';

CREATE OR REPLACE FUNCTION finance.guard_payout_review_hold()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE v_status platform.payout_status;
BEGIN
    IF NEW.status = 'ACTIVE' THEN
        SELECT status INTO v_status FROM finance.payout WHERE id = NEW.payout_id FOR UPDATE;
        IF NOT FOUND THEN
            RAISE EXCEPTION 'payout not found';
        END IF;
        IF v_status <> 'PENDING_HOLD' THEN
            RAISE EXCEPTION 'objective incident hold may only be attached while payout is PENDING_HOLD';
        END IF;
    END IF;
    RETURN NEW;
END;
$$;

DROP TRIGGER IF EXISTS payout_review_hold_guard ON finance.payout_hold;
CREATE TRIGGER payout_review_hold_guard
BEFORE INSERT OR UPDATE OF status ON finance.payout_hold
FOR EACH ROW EXECUTE FUNCTION finance.guard_payout_review_hold();

CREATE OR REPLACE FUNCTION finance.guard_payout_available()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.status = 'AVAILABLE' AND (TG_OP = 'INSERT' OR OLD.status IS DISTINCT FROM NEW.status) THEN
        IF clock_timestamp() < NEW.pending_until THEN
            RAISE EXCEPTION 'payout cannot become AVAILABLE before its 60-minute hold expires';
        END IF;
        IF EXISTS (
            SELECT 1 FROM finance.payout_hold h
             WHERE h.payout_id = NEW.id AND h.status = 'ACTIVE'
        ) THEN
            RAISE EXCEPTION 'payout has an active objective incident hold';
        END IF;
        IF NEW.available_at IS NULL OR NEW.availability_transaction_id IS NULL THEN
            RAISE EXCEPTION 'AVAILABLE payout requires availability ledger transaction and timestamp';
        END IF;
    END IF;
    RETURN NEW;
END;
$$;

DROP TRIGGER IF EXISTS payout_available_guard ON finance.payout;
CREATE TRIGGER payout_available_guard
BEFORE INSERT OR UPDATE OF status ON finance.payout
FOR EACH ROW EXECUTE FUNCTION finance.guard_payout_available();

CREATE INDEX IF NOT EXISTS session_settlement_status_idx
ON finance.session_settlement(status, created_at);

CREATE INDEX IF NOT EXISTS payout_pending_release_idx
ON finance.payout(pending_until, id)
WHERE status = 'PENDING_HOLD';

COMMIT;
