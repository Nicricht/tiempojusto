
CREATE TRIGGER safety_case_irreversible_normalize
BEFORE INSERT OR UPDATE OF severity, reversible, decision_status ON safety.safety_case
FOR EACH ROW EXECUTE FUNCTION safety.normalize_irreversible_case();

CREATE OR REPLACE FUNCTION safety.enqueue_irreversible_review()
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
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER safety_case_irreversible_queue
AFTER INSERT OR UPDATE OF severity, reversible ON safety.safety_case
FOR EACH ROW EXECUTE FUNCTION safety.enqueue_irreversible_review();

CREATE TABLE safety.risk_signal (
    id bigserial PRIMARY KEY,
    user_id uuid REFERENCES iam.app_user(id) ON DELETE SET NULL,
    signal_type varchar(80) NOT NULL,
    score numeric(6,3),
    source_ref text,
    created_at timestamptz NOT NULL DEFAULT now()
);

COMMENT ON TABLE safety.risk_signal IS
'Private Risk signal. Never expose as reputation/public score and never use beauty inference.';

-- --------------------------------------------------------------------------
-- 12. Comms
-- --------------------------------------------------------------------------
CREATE TABLE comms.conversation (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    appointment_id uuid REFERENCES appointment.appointment(id) ON DELETE SET NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    closed_at timestamptz,
    CONSTRAINT conversation_close_ck CHECK (closed_at IS NULL OR closed_at >= created_at)
);

CREATE TABLE comms.conversation_member (
    conversation_id uuid NOT NULL REFERENCES comms.conversation(id) ON DELETE CASCADE,
    user_id uuid NOT NULL REFERENCES iam.app_user(id) ON DELETE CASCADE,
    joined_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (conversation_id, user_id)
);

CREATE TABLE comms.message (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    conversation_id uuid NOT NULL REFERENCES comms.conversation(id) ON DELETE CASCADE,
    sender_user_id uuid NOT NULL REFERENCES iam.app_user(id) ON DELETE RESTRICT,
    body text NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    deleted_at timestamptz,
    CONSTRAINT message_delete_ck CHECK (deleted_at IS NULL OR deleted_at >= created_at)
);

CREATE TABLE comms.structured_request (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    appointment_id uuid NOT NULL REFERENCES appointment.appointment(id) ON DELETE CASCADE,
    request_type varchar(50) NOT NULL,
    created_by_user_id uuid NOT NULL REFERENCES iam.app_user(id) ON DELETE RESTRICT,
    payload jsonb NOT NULL,
    status platform.request_status NOT NULL DEFAULT 'PENDING',
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE comms.notification (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id uuid NOT NULL REFERENCES iam.app_user(id) ON DELETE CASCADE,
    type varchar(80) NOT NULL,
    payload jsonb NOT NULL,
    read_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT notification_read_ck CHECK (read_at IS NULL OR read_at >= created_at)
);

-- --------------------------------------------------------------------------
-- 13. Reputation
-- --------------------------------------------------------------------------
CREATE TABLE reputation.rating (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    appointment_id uuid NOT NULL REFERENCES appointment.appointment(id) ON DELETE CASCADE,
    from_user_id uuid NOT NULL REFERENCES iam.app_user(id) ON DELETE RESTRICT,
    to_user_id uuid NOT NULL REFERENCES iam.app_user(id) ON DELETE RESTRICT,
    score smallint NOT NULL CHECK (score BETWEEN 1 AND 5),
    comment text,
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (appointment_id, from_user_id, to_user_id),
    CONSTRAINT rating_not_self_ck CHECK (from_user_id <> to_user_id)
);

COMMENT ON TABLE reputation.rating IS
'V1.7: rating must not evaluate body, intimacy, kissing or sexual performance. Content policy/service validation remains required.';

CREATE TABLE reputation.reputation_snapshot (
    id bigserial PRIMARY KEY,
    user_id uuid NOT NULL REFERENCES iam.app_user(id) ON DELETE CASCADE,
    score numeric(6,3) NOT NULL CHECK (score BETWEEN 0 AND 100),
    completed_count integer NOT NULL CHECK (completed_count >= 0),
    no_show_count integer NOT NULL CHECK (no_show_count >= 0),
    report_weight numeric(8,3) NOT NULL DEFAULT 0 CHECK (report_weight >= 0),
    calculated_at timestamptz NOT NULL DEFAULT now()
);

-- --------------------------------------------------------------------------
-- 14. Platform: audit, admin, outbox, idempotency
-- --------------------------------------------------------------------------
CREATE TABLE platform.audit_event (
    id bigserial PRIMARY KEY,
    actor_user_id uuid REFERENCES iam.app_user(id) ON DELETE SET NULL,
    event_type varchar(100) NOT NULL,
    entity_type varchar(80) NOT NULL,
    entity_id uuid,
    before_hash text,
    after_hash text,
    metadata jsonb,
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE OR REPLACE FUNCTION platform.reject_mutation()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION '% is immutable', TG_TABLE_SCHEMA || '.' || TG_TABLE_NAME;
END;
$$;

CREATE TRIGGER audit_event_immutable
BEFORE UPDATE OR DELETE ON platform.audit_event
FOR EACH ROW EXECUTE FUNCTION platform.reject_mutation();

CREATE TABLE platform.admin_action (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    admin_user_id uuid NOT NULL REFERENCES iam.app_user(id) ON DELETE RESTRICT,
    action_type varchar(80) NOT NULL,
    target_type varchar(80) NOT NULL,
    target_id uuid NOT NULL,
    reason text NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE platform.outbox_event (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    aggregate_type varchar(80) NOT NULL,
    aggregate_id uuid NOT NULL,
    event_type varchar(120) NOT NULL,
    event_version smallint NOT NULL DEFAULT 1 CHECK (event_version > 0),
    payload jsonb NOT NULL,
    occurred_at timestamptz NOT NULL DEFAULT now(),
    published_at timestamptz,
    attempt_count integer NOT NULL DEFAULT 0 CHECK (attempt_count >= 0),
    CONSTRAINT outbox_publish_ck CHECK (published_at IS NULL OR published_at >= occurred_at)
);
