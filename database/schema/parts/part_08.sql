
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
CREATE OR REPLACE FUNCTION geo.purge_expired_operational_locations(p_limit integer DEFAULT 10000)
RETURNS integer
LANGUAGE plpgsql
AS $$
DECLARE v_deleted integer;
BEGIN
    WITH doomed AS (
        SELECT id
          FROM geo.operational_location_event
         WHERE preservation_hold = false
           AND purge_after <= clock_timestamp()
         ORDER BY purge_after
         LIMIT p_limit
         FOR UPDATE SKIP LOCKED
    )
    DELETE FROM geo.operational_location_event g
     USING doomed d
     WHERE g.id = d.id;
    GET DIAGNOSTICS v_deleted = ROW_COUNT;
    RETURN v_deleted;
END;
$$;

CREATE OR REPLACE FUNCTION platform.purge_expired_idempotency(p_limit integer DEFAULT 10000)
RETURNS integer
LANGUAGE plpgsql
AS $$
DECLARE v_deleted integer;
BEGIN
    WITH doomed AS (
        SELECT id
          FROM platform.idempotency_record
         WHERE expires_at <= clock_timestamp()
         ORDER BY expires_at
         LIMIT p_limit
         FOR UPDATE SKIP LOCKED
    )
    DELETE FROM platform.idempotency_record r
     USING doomed d
     WHERE r.id = d.id;
    GET DIAGNOSTICS v_deleted = ROW_COUNT;
    RETURN v_deleted;
END;
$$;

-- --------------------------------------------------------------------------
-- 17. Comments on rules intentionally left for P0.3/P0.4/provider adapters
-- --------------------------------------------------------------------------
COMMENT ON SCHEMA auction IS
'Backend/server remains source of truth. Full auction state machine, risk decisions, reservation release and winner/supplement chain are finalized in P0.3/P0.4; DB already serializes accepted Bid insertion by locking Auction root.';

COMMENT ON SCHEMA appointment IS
'Arrival never authorizes billing by itself. FREE/PAID transitions require the defined Handshake/Online bilateral flows and are completed in executable state machines.';

COMMENT ON SCHEMA finance IS
'V1.7 split rules: session 80% HOST / 20% TiempoJusto on amount actually generated; Live Ticket 70%/30% on distributable revenue. Exact settlement postings belong to PaymentPort + ledger service; DB enforces arithmetic/balance/hold but does not invent provider fees.';

COMMENT ON SCHEMA media IS
'Online V1.7: join 3m, FREE_ONLINE 2m, camera required, bilateral paid confirmation 30s, media tolerance 5s, reconnect 2m, no private recording by default. Media-tolerance semantics depend on WebRTC heartbeats and are finalized in P0.4.';

COMMENT ON SCHEMA safety IS
'V1.7 Safety: S0 no infringement; S1 warning; S2 24h; S3 7d; S4 30d; S5 indefinite/closure. Levels may be skipped for severity. S5/irreversible requires HumanReviewQueue; appeal window 7 days.';

-- --------------------------------------------------------------------------
-- 18. Sanity checks for schema deployment
-- --------------------------------------------------------------------------
DO $$
BEGIN
    IF platform.next_minimum_bid(100000) <> 110000 THEN
        RAISE EXCEPTION 'next_minimum_bid sanity check failed';
    END IF;
    IF platform.next_minimum_bid(10000) <> 15000 THEN
        RAISE EXCEPTION 'minimum increment sanity check failed';
    END IF;
    IF NOT platform.valid_base_duration('IN_PERSON'::platform.modality, 60::smallint) OR platform.valid_base_duration('ONLINE'::platform.modality, 90::smallint) THEN
        RAISE EXCEPTION 'duration sanity check failed';
    END IF;
END;
$$;

COMMIT;

-- ============================================================================
-- END V1.0
-- Known next milestones:
-- P0.3 locking/idempotency/outbox transaction contracts (service-level details)
-- P0.4 executable state machines and guards
-- P0.5 OpenAPI definitive contracts
-- P0.6 WebSocket topics/resync/versioning
-- ============================================================================
