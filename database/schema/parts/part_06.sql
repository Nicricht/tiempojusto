
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
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT safety_decision_ck CHECK (decided_at IS NULL OR decided_at >= created_at),
    CONSTRAINT safety_appeal_window_ck CHECK (appeal_deadline IS NULL OR decided_at IS NULL OR appeal_deadline = decided_at + interval '7 days')
);

CREATE TABLE safety.evidence_item (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    report_id uuid REFERENCES safety.report(id) ON DELETE SET NULL,
    safety_case_id uuid REFERENCES safety.safety_case(id) ON DELETE CASCADE,
    evidence_type platform.evidence_type NOT NULL,
    storage_ref text,
    content_hash text,
    preserve_until timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT evidence_parent_ck CHECK (report_id IS NOT NULL OR safety_case_id IS NOT NULL)
);

CREATE TABLE safety.appeal (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    safety_case_id uuid NOT NULL REFERENCES safety.safety_case(id) ON DELETE CASCADE,
    appellant_user_id uuid NOT NULL REFERENCES iam.app_user(id) ON DELETE RESTRICT,
    reason_text text,
    status platform.appeal_status NOT NULL DEFAULT 'OPEN',
    submitted_at timestamptz NOT NULL DEFAULT now(),
    resolved_at timestamptz,
    CONSTRAINT appeal_resolution_ck CHECK (resolved_at IS NULL OR resolved_at >= submitted_at)
);

CREATE UNIQUE INDEX one_open_appeal_per_case_uidx
ON safety.appeal(safety_case_id)
WHERE status = 'OPEN';

CREATE OR REPLACE FUNCTION safety.guard_appeal_deadline()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE v_deadline timestamptz; v_subject uuid;
BEGIN
    SELECT appeal_deadline, subject_user_id INTO v_deadline, v_subject
      FROM safety.safety_case
     WHERE id = NEW.safety_case_id;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'safety_case not found';
    END IF;
    IF NEW.appellant_user_id <> v_subject THEN
        RAISE EXCEPTION 'only the subject user may appeal this safety case';
    END IF;
    IF NEW.status = 'OPEN' AND (v_deadline IS NULL OR NEW.submitted_at > v_deadline) THEN
        RAISE EXCEPTION 'normal appeal is outside the 7-day appeal window';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER appeal_deadline_guard
BEFORE INSERT OR UPDATE OF status, submitted_at ON safety.appeal
FOR EACH ROW EXECUTE FUNCTION safety.guard_appeal_deadline();

CREATE TABLE safety.human_review_task (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    safety_case_id uuid NOT NULL REFERENCES safety.safety_case(id) ON DELETE CASCADE,
    task_type varchar(50) NOT NULL,
    status platform.review_task_status NOT NULL DEFAULT 'QUEUED',
    assigned_admin_user_id uuid REFERENCES iam.app_user(id) ON DELETE SET NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    resolved_at timestamptz,
    CONSTRAINT review_resolution_ck CHECK (resolved_at IS NULL OR resolved_at >= created_at)
);

CREATE UNIQUE INDEX active_human_review_per_case_uidx
ON safety.human_review_task(safety_case_id)
WHERE status IN ('QUEUED','IN_PROGRESS');

CREATE OR REPLACE FUNCTION safety.ensure_human_review_for_irreversible()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.severity = 'S5' OR NEW.reversible = false THEN
        INSERT INTO safety.human_review_task(safety_case_id, task_type, status)
        SELECT NEW.id,
               CASE WHEN NEW.severity = 'S5' THEN 'S5' ELSE 'IRREVERSIBLE' END,
               'QUEUED'
        WHERE NOT EXISTS (
            SELECT 1 FROM safety.human_review_task
             WHERE safety_case_id = NEW.id
               AND status IN ('QUEUED','IN_PROGRESS')
        );
        IF NEW.decision_status = 'AUTO_ACTIONED' THEN
            NEW.decision_status := 'HUMAN_REVIEW';
        END IF;
    END IF;
    RETURN NEW;
END;
$$;

-- BEFORE is required to keep decision_status aligned. Queue insertion is done AFTER.
CREATE OR REPLACE FUNCTION safety.normalize_irreversible_case()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF (NEW.severity = 'S5' OR NEW.reversible = false) AND NEW.decision_status = 'AUTO_ACTIONED' THEN
        NEW.decision_status := 'HUMAN_REVIEW';
    END IF;
    RETURN NEW;
END;
$$;
