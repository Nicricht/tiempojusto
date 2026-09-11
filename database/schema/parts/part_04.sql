
CREATE TABLE appointment.meeting_handshake (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    appointment_id uuid NOT NULL UNIQUE REFERENCES appointment.appointment(id) ON DELETE CASCADE,
    token_hash text NOT NULL UNIQUE,
    host_confirmed_at timestamptz,
    bidder_confirmed_at timestamptz,
    completed_at timestamptz,
    expires_at timestamptz NOT NULL,
    status platform.handshake_status NOT NULL DEFAULT 'PENDING',
    CONSTRAINT handshake_bilateral_ck CHECK (
        completed_at IS NULL OR (host_confirmed_at IS NOT NULL AND bidder_confirmed_at IS NOT NULL)
    ),
    CONSTRAINT handshake_completed_state_ck CHECK (
        status <> 'COMPLETED' OR completed_at IS NOT NULL
    )
);

CREATE TABLE appointment.appointment_session (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    appointment_id uuid NOT NULL UNIQUE REFERENCES appointment.appointment(id) ON DELETE CASCADE,
    session_mode platform.modality NOT NULL,
    status platform.session_status NOT NULL DEFAULT 'WAITING',
    free_started_at timestamptz,
    free_ends_at timestamptz,
    paid_started_at timestamptz,
    ended_at timestamptz,
    billable_seconds integer NOT NULL DEFAULT 0 CHECK (billable_seconds >= 0),
    lock_version integer NOT NULL DEFAULT 0 CHECK (lock_version >= 0),
    CONSTRAINT session_free_duration_ck CHECK (
        free_ends_at IS NULL OR (
            free_started_at IS NOT NULL AND (
                (session_mode = 'IN_PERSON' AND free_ends_at = free_started_at + interval '5 minutes') OR
                (session_mode = 'ONLINE' AND free_ends_at = free_started_at + interval '2 minutes')
            )
        )
    ),
    CONSTRAINT session_end_ck CHECK (ended_at IS NULL OR free_started_at IS NULL OR ended_at >= free_started_at)
);

CREATE TABLE appointment.session_segment (
    id bigserial PRIMARY KEY,
    session_id uuid NOT NULL REFERENCES appointment.appointment_session(id) ON DELETE CASCADE,
    segment_type platform.segment_type NOT NULL,
    started_at timestamptz NOT NULL,
    ended_at timestamptz,
    billable boolean NOT NULL,
    billable_seconds integer NOT NULL DEFAULT 0 CHECK (billable_seconds >= 0),
    CONSTRAINT session_segment_end_ck CHECK (ended_at IS NULL OR ended_at >= started_at),
    CONSTRAINT session_segment_billable_ck CHECK (
        (segment_type = 'PAID' AND billable = true)
        OR (segment_type IN ('FREE','PAUSE','RECONNECT') AND billable = false)
    )
);

CREATE TABLE appointment.presence_event (
    id bigserial PRIMARY KEY,
    session_id uuid NOT NULL REFERENCES appointment.appointment_session(id) ON DELETE CASCADE,
    user_id uuid NOT NULL REFERENCES iam.app_user(id) ON DELETE RESTRICT,
    event_type varchar(40) NOT NULL,
    server_at timestamptz NOT NULL DEFAULT now(),
    client_at timestamptz,
    metadata jsonb
);

CREATE TABLE appointment.session_extension_negotiation (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    session_id uuid NOT NULL REFERENCES appointment.appointment_session(id) ON DELETE CASCADE,
    round_no smallint NOT NULL CHECK (round_no BETWEEN 1 AND 3),
    status platform.extension_status NOT NULL DEFAULT 'OPEN',
    expires_at timestamptz NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (session_id, round_no),
    CONSTRAINT extension_negotiation_expiry_ck CHECK (expires_at > created_at)
);

CREATE UNIQUE INDEX one_open_extension_negotiation_uidx
ON appointment.session_extension_negotiation(session_id)
WHERE status = 'OPEN';

CREATE TABLE appointment.extension_offer (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    negotiation_id uuid NOT NULL REFERENCES appointment.session_extension_negotiation(id) ON DELETE CASCADE,
    offered_by_user_id uuid NOT NULL REFERENCES iam.app_user(id) ON DELETE RESTRICT,
    extra_minutes smallint NOT NULL CHECK (extra_minutes IN (15,30)),
    amount_clp bigint NOT NULL CHECK (amount_clp > 0 AND amount_clp % 5000 = 0),
    funds_reservation_id uuid REFERENCES auction.funds_reservation(id) ON DELETE RESTRICT,
    status platform.offer_status NOT NULL DEFAULT 'PENDING',
    created_at timestamptz NOT NULL DEFAULT now()
);

-- --------------------------------------------------------------------------
-- 9. Live / Ticket / Online room
-- --------------------------------------------------------------------------
CREATE TABLE media.live_session (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    auction_id uuid NOT NULL UNIQUE REFERENCES auction.auction(id) ON DELETE CASCADE,
    host_user_id uuid NOT NULL REFERENCES iam.app_user(id) ON DELETE RESTRICT,
    status platform.live_status NOT NULL DEFAULT 'STARTING',
    started_at timestamptz,
    ended_at timestamptz,
    provider_room_ref varchar(160),
    lock_version integer NOT NULL DEFAULT 0 CHECK (lock_version >= 0),
    CONSTRAINT live_session_end_ck CHECK (ended_at IS NULL OR started_at IS NULL OR ended_at >= started_at)
);

CREATE OR REPLACE FUNCTION media.guard_live_start()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE v_end timestamptz; v_status platform.auction_status;
BEGIN
    IF NEW.status = 'LIVE' AND (TG_OP = 'INSERT' OR OLD.status IS DISTINCT FROM NEW.status) THEN
        NEW.started_at := COALESCE(NEW.started_at, clock_timestamp());
        SELECT effective_end_at, status INTO v_end, v_status FROM auction.auction WHERE id = NEW.auction_id;
        IF v_status <> 'OPEN' OR v_end - NEW.started_at < interval '5 minutes' THEN
            RAISE EXCEPTION 'Live may start only on an open Auction with at least 5 minutes remaining';
        END IF;
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER live_start_guard
BEFORE INSERT OR UPDATE OF status ON media.live_session
FOR EACH ROW EXECUTE FUNCTION media.guard_live_start();

CREATE TABLE media.live_access (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    live_session_id uuid NOT NULL REFERENCES media.live_session(id) ON DELETE CASCADE,
    user_id uuid NOT NULL REFERENCES iam.app_user(id) ON DELETE RESTRICT,
    access_type platform.live_access_type NOT NULL,
    granted_at timestamptz NOT NULL DEFAULT now(),
    revoked_at timestamptz,
    UNIQUE (live_session_id, user_id)
);

CREATE TABLE media.live_ticket (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    live_access_id uuid NOT NULL UNIQUE REFERENCES media.live_access(id) ON DELETE CASCADE,
    buyer_user_id uuid NOT NULL REFERENCES iam.app_user(id) ON DELETE RESTRICT,
    amount_clp bigint NOT NULL,
    remaining_seconds_at_purchase integer NOT NULL,
    funds_reservation_id uuid REFERENCES auction.funds_reservation(id) ON DELETE RESTRICT,
    status platform.ticket_status NOT NULL,
    purchased_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT live_ticket_cutoff_ck CHECK (remaining_seconds_at_purchase >= 180),
    CONSTRAINT live_ticket_price_ck CHECK (
        (remaining_seconds_at_purchase >= 480 AND amount_clp = 1500)
        OR (remaining_seconds_at_purchase BETWEEN 180 AND 479 AND amount_clp = 1000)
    )
);

CREATE TABLE media.video_room (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    appointment_id uuid NOT NULL UNIQUE REFERENCES appointment.appointment(id) ON DELETE CASCADE,
    provider_room_ref varchar(160) UNIQUE,
    status platform.video_room_status NOT NULL DEFAULT 'WAITING',
    join_deadline timestamptz NOT NULL,
    free_online_end timestamptz,
    paid_acceptance_deadline timestamptz,
    media_lost_at timestamptz,
    reconnect_deadline timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT video_join_3m_ck CHECK (join_deadline = created_at + interval '3 minutes'),
    CONSTRAINT video_reconnect_2m_ck CHECK (reconnect_deadline IS NULL OR (media_lost_at IS NOT NULL AND reconnect_deadline = media_lost_at + interval '2 minutes'))
);
