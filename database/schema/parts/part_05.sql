    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    transaction_type platform.ledger_tx_type NOT NULL,
    reference_type varchar(40) NOT NULL,
    reference_id uuid,
    status platform.ledger_tx_status NOT NULL DEFAULT 'PENDING',
    idempotency_key text NOT NULL UNIQUE,
    created_at timestamptz NOT NULL DEFAULT now(),
    posted_at timestamptz,
    CONSTRAINT ledger_posted_ck CHECK (status <> 'POSTED' OR posted_at IS NOT NULL)
);

CREATE TABLE finance.ledger_entry (
    id bigserial PRIMARY KEY,
    transaction_id uuid NOT NULL REFERENCES finance.ledger_transaction(id) ON DELETE RESTRICT,
    account_id uuid NOT NULL REFERENCES finance.ledger_account(id) ON DELETE RESTRICT,
    direction platform.entry_direction NOT NULL,
    amount_clp bigint NOT NULL CHECK (amount_clp > 0),
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE OR REPLACE FUNCTION finance.guard_ledger_entry()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE v_status platform.ledger_tx_status;
BEGIN
    IF TG_OP IN ('UPDATE','DELETE') THEN
        RAISE EXCEPTION 'ledger_entry is immutable; use a reversing transaction';
    END IF;

    SELECT status INTO v_status FROM finance.ledger_transaction WHERE id = NEW.transaction_id;
    IF v_status IN ('POSTED','REVERSED') THEN
        RAISE EXCEPTION 'cannot add entries to a finalized ledger transaction';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER ledger_entry_guard
BEFORE INSERT OR UPDATE OR DELETE ON finance.ledger_entry
FOR EACH ROW EXECUTE FUNCTION finance.guard_ledger_entry();

CREATE OR REPLACE FUNCTION finance.assert_ledger_balanced()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE v_debit bigint; v_credit bigint; v_count integer;
BEGIN
    IF NEW.status <> 'POSTED' THEN
        RETURN NEW;
    END IF;

    SELECT
        COALESCE(SUM(amount_clp) FILTER (WHERE direction = 'DEBIT'),0),
        COALESCE(SUM(amount_clp) FILTER (WHERE direction = 'CREDIT'),0),
        COUNT(*)
      INTO v_debit, v_credit, v_count
      FROM finance.ledger_entry
     WHERE transaction_id = NEW.id;

    IF v_count < 2 OR v_debit <> v_credit OR v_debit = 0 THEN
        RAISE EXCEPTION 'POSTED ledger transaction % is not balanced', NEW.id;
    END IF;
    RETURN NEW;
END;
$$;

CREATE CONSTRAINT TRIGGER ledger_balance_guard
AFTER INSERT OR UPDATE OF status ON finance.ledger_transaction
DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW EXECUTE FUNCTION finance.assert_ledger_balanced();

CREATE TABLE finance.refund (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    transaction_id uuid NOT NULL REFERENCES finance.ledger_transaction(id) ON DELETE RESTRICT,
    appointment_id uuid REFERENCES appointment.appointment(id) ON DELETE SET NULL,
    user_id uuid NOT NULL REFERENCES iam.app_user(id) ON DELETE RESTRICT,
    amount_clp bigint NOT NULL CHECK (amount_clp > 0),
    reason_code varchar(50) NOT NULL,
    provider_reference varchar(160),
    status platform.refund_status NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE finance.payout (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    host_user_id uuid NOT NULL REFERENCES iam.app_user(id) ON DELETE RESTRICT,
    source_transaction_id uuid NOT NULL REFERENCES finance.ledger_transaction(id) ON DELETE RESTRICT,
    gross_amount_clp bigint NOT NULL CHECK (gross_amount_clp >= 0),
    platform_fee_clp bigint NOT NULL CHECK (platform_fee_clp >= 0),
    net_amount_clp bigint NOT NULL CHECK (net_amount_clp >= 0),
    pending_until timestamptz NOT NULL,
    status platform.payout_status NOT NULL DEFAULT 'PENDING_HOLD',
    provider_reference varchar(160),
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT payout_amounts_ck CHECK (gross_amount_clp = platform_fee_clp + net_amount_clp)
);

CREATE OR REPLACE FUNCTION finance.guard_payout_hold()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE v_posted timestamptz;
BEGIN
    SELECT posted_at INTO v_posted FROM finance.ledger_transaction WHERE id = NEW.source_transaction_id;
    IF v_posted IS NULL THEN
        RAISE EXCEPTION 'payout source transaction must be POSTED';
    END IF;
    IF NEW.pending_until < v_posted + interval '60 minutes' THEN
        RAISE EXCEPTION 'payout pending_until violates the 60-minute hold';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER payout_hold_guard
BEFORE INSERT OR UPDATE OF source_transaction_id, pending_until ON finance.payout
FOR EACH ROW EXECUTE FUNCTION finance.guard_payout_hold();

CREATE TABLE finance.payment_dispute (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    provider_reference varchar(160) NOT NULL UNIQUE,
    user_id uuid REFERENCES iam.app_user(id) ON DELETE SET NULL,
    appointment_id uuid REFERENCES appointment.appointment(id) ON DELETE SET NULL,
    amount_clp bigint NOT NULL CHECK (amount_clp > 0),
    status platform.dispute_status NOT NULL,
    opened_at timestamptz NOT NULL,
    closed_at timestamptz,
    evidence_bundle_ref text,
    CONSTRAINT dispute_window_ck CHECK (closed_at IS NULL OR closed_at >= opened_at)
);

COMMENT ON TABLE finance.payment_dispute IS
'V1.7: a chargeback does not automatically create debt for a compliant HOST. Recovery requires objective/audited grounds.';

CREATE TABLE finance.penalty (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id uuid NOT NULL REFERENCES iam.app_user(id) ON DELETE RESTRICT,
    appointment_id uuid REFERENCES appointment.appointment(id) ON DELETE SET NULL,
    amount_clp bigint NOT NULL CHECK (amount_clp >= 0),
    reason_code varchar(50) NOT NULL,
    transaction_id uuid REFERENCES finance.ledger_transaction(id) ON DELETE RESTRICT,
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE finance.compensation (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    beneficiary_user_id uuid NOT NULL REFERENCES iam.app_user(id) ON DELETE RESTRICT,
    appointment_id uuid REFERENCES appointment.appointment(id) ON DELETE SET NULL,
    amount_clp bigint NOT NULL CHECK (amount_clp >= 0),
    reason_code varchar(50) NOT NULL,
    transaction_id uuid REFERENCES finance.ledger_transaction(id) ON DELETE RESTRICT,
    created_at timestamptz NOT NULL DEFAULT now()
);

-- --------------------------------------------------------------------------
-- 11. Safety / Risk / Appeals
-- --------------------------------------------------------------------------
CREATE TABLE safety.report (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    reporter_user_id uuid NOT NULL REFERENCES iam.app_user(id) ON DELETE RESTRICT,
    reported_user_id uuid REFERENCES iam.app_user(id) ON DELETE SET NULL,
    appointment_id uuid REFERENCES appointment.appointment(id) ON DELETE SET NULL,
    category varchar(50) NOT NULL,
    description text,
    status platform.report_status NOT NULL DEFAULT 'OPEN',
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT report_not_self_ck CHECK (reported_user_id IS NULL OR reported_user_id <> reporter_user_id)
);

CREATE TABLE safety.safety_case (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    subject_user_id uuid NOT NULL REFERENCES iam.app_user(id) ON DELETE RESTRICT,
    report_id uuid REFERENCES safety.report(id) ON DELETE SET NULL,
    severity platform.safety_level NOT NULL,
    decision_status platform.case_decision_status NOT NULL DEFAULT 'OPEN',
    action_code varchar(50),
    reversible boolean NOT NULL,
    decided_at timestamptz,
    appeal_deadline timestamptz,
