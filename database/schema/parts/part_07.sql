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

CREATE TABLE platform.idempotency_record (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id uuid REFERENCES iam.app_user(id) ON DELETE SET NULL,
    scope varchar(80) NOT NULL,
    idempotency_key text NOT NULL,
    request_hash text NOT NULL,
    response_code integer,
    response_body jsonb,
    created_at timestamptz NOT NULL DEFAULT now(),
    expires_at timestamptz NOT NULL,
    CONSTRAINT idempotency_expiry_ck CHECK (expires_at > created_at)
);

CREATE UNIQUE INDEX idempotency_scope_key_uidx
ON platform.idempotency_record(
    COALESCE(user_id, '00000000-0000-0000-0000-000000000000'::uuid),
    scope,
    idempotency_key
);

-- --------------------------------------------------------------------------
-- 15. Minimum indexes from ER V1.0 + high-value FK indexes
-- --------------------------------------------------------------------------
CREATE INDEX identity_verification_user_idx ON iam.identity_verification(user_id, created_at DESC);
CREATE INDEX device_user_idx ON iam.device(user_id, last_seen_at DESC);

CREATE INDEX approximate_location_profile_idx ON geo.approximate_location(profile_id, valid_from DESC);
CREATE INDEX approximate_location_gist ON geo.approximate_location USING gist(centroid);
CREATE INDEX operational_geo_gist ON geo.operational_location_event USING gist(point);
CREATE INDEX operational_geo_purge_idx ON geo.operational_location_event(purge_after) WHERE preservation_hold = false;
CREATE INDEX operational_geo_appointment_idx ON geo.operational_location_event(appointment_id, captured_at DESC);

CREATE INDEX proposal_active_market_idx
ON market.proposal(host_profile_id, modality, duration_minutes, amount_clp DESC)
WHERE status = 'ACTIVE';
CREATE INDEX proposal_bidder_idx ON market.proposal(bidder_user_id, status, valid_until);
CREATE INDEX proposal_metric_latest_idx ON market.proposal_metric_snapshot(host_profile_id, modality, duration_minutes, calculated_at DESC);
CREATE INDEX availability_active_geo_idx ON market.now_availability(status, modality, activated_at DESC);
CREATE INDEX meta_active_deadline_idx ON market.meta_now(deadline_at) WHERE status = 'ACTIVE';

CREATE INDEX auction_open_end_idx ON auction.auction(status, effective_end_at) WHERE status = 'OPEN';
CREATE INDEX auction_host_idx ON auction.auction(host_user_id, started_at DESC);
CREATE INDEX auction_participant_bidder_idx ON auction.auction_participant(bidder_user_id, joined_at DESC);
CREATE INDEX bid_auction_seq_idx ON auction.bid(auction_id, server_sequence);
CREATE INDEX bid_auction_amount_idx ON auction.bid(auction_id, amount_clp DESC, created_at);
CREATE INDEX bid_bidder_idx ON auction.bid(bidder_user_id, created_at DESC);
CREATE INDEX reservation_provider_ref_idx ON auction.funds_reservation(provider_code, provider_reference);
CREATE INDEX reservation_user_status_idx ON auction.funds_reservation(user_id, status, expires_at);

CREATE INDEX appointment_party_idx ON appointment.appointment(host_user_id, bidder_user_id, created_at DESC);
CREATE INDEX arrival_appointment_idx ON appointment.arrival_check_in(appointment_id, checked_at DESC);
CREATE INDEX session_segment_time_idx ON appointment.session_segment(session_id, started_at);
CREATE INDEX presence_session_time_idx ON appointment.presence_event(session_id, server_at);
CREATE INDEX extension_offer_negotiation_idx ON appointment.extension_offer(negotiation_id, created_at);

CREATE INDEX live_access_user_idx ON media.live_access(user_id, granted_at DESC);
CREATE INDEX live_ticket_buyer_idx ON media.live_ticket(buyer_user_id, purchased_at DESC);
CREATE INDEX video_event_room_time_idx ON media.video_participant_event(video_room_id, server_at);

CREATE INDEX ledger_transaction_ref_idx ON finance.ledger_transaction(reference_type, reference_id, created_at);
CREATE INDEX ledger_account_time_idx ON finance.ledger_entry(account_id, created_at, id);
CREATE INDEX ledger_entry_tx_idx ON finance.ledger_entry(transaction_id, id);
CREATE INDEX payout_due_idx ON finance.payout(status, pending_until) WHERE status = 'PENDING_HOLD';
CREATE INDEX dispute_appointment_idx ON finance.payment_dispute(appointment_id, opened_at DESC);

CREATE INDEX report_subject_idx ON safety.report(reported_user_id, created_at DESC);
CREATE INDEX safety_case_subject_idx ON safety.safety_case(subject_user_id, created_at DESC);
CREATE INDEX safety_queue_idx ON safety.human_review_task(status, created_at);
CREATE INDEX risk_signal_user_idx ON safety.risk_signal(user_id, created_at DESC);

CREATE INDEX conversation_appointment_idx ON comms.conversation(appointment_id);
CREATE INDEX message_conversation_time_idx ON comms.message(conversation_id, created_at);
CREATE INDEX notification_user_idx ON comms.notification(user_id, read_at, created_at DESC);

CREATE INDEX rating_target_idx ON reputation.rating(to_user_id, created_at DESC);
CREATE INDEX reputation_latest_idx ON reputation.reputation_snapshot(user_id, calculated_at DESC);

CREATE INDEX audit_entity_idx ON platform.audit_event(entity_type, entity_id, created_at DESC);
CREATE INDEX outbox_pending_idx ON platform.outbox_event(occurred_at) WHERE published_at IS NULL;
CREATE INDEX idempotency_expiry_idx ON platform.idempotency_record(expires_at);

-- --------------------------------------------------------------------------
-- 16. Retention helpers (scheduler/job must call them)
-- --------------------------------------------------------------------------
