        RAISE EXCEPTION 'auction deadline has passed';
    END IF;

    SELECT eligibility_status INTO v_elig
      FROM auction.auction_participant
     WHERE auction_id = NEW.auction_id
       AND bidder_user_id = NEW.bidder_user_id;

    IF v_elig IS DISTINCT FROM 'ELIGIBLE'::platform.eligibility_status THEN
        RAISE EXCEPTION 'bidder is not eligible for this auction';
    END IF;

    IF NEW.status IN ('VALID','WINNING') THEN
        IF NEW.funds_reservation_id IS NULL THEN
            RAISE EXCEPTION 'accepted bid requires a funds reservation';
        END IF;
        SELECT * INTO v_res FROM auction.funds_reservation WHERE id = NEW.funds_reservation_id FOR UPDATE;
        IF NOT FOUND OR v_res.status <> 'RESERVED' OR v_res.user_id <> NEW.bidder_user_id OR v_res.amount_clp < NEW.amount_clp THEN
            RAISE EXCEPTION 'funds reservation is not valid for this bid';
        END IF;

        IF NEW.bid_type = 'NORMAL' AND NEW.amount_clp < v_auc.next_actionable_amount_clp THEN
            RAISE EXCEPTION 'bid amount is below next actionable amount %', v_auc.next_actionable_amount_clp;
        END IF;

        IF NEW.bid_type = 'INSTANT_CLOSE' AND (v_auc.instant_close_amount_clp IS NULL OR NEW.amount_clp <> v_auc.instant_close_amount_clp) THEN
            RAISE EXCEPTION 'instant close amount does not match frozen auction amount';
        END IF;
    END IF;

    SELECT COALESCE(MAX(server_sequence), 0) + 1 INTO v_next_seq
      FROM auction.bid
     WHERE auction_id = NEW.auction_id;
    NEW.server_sequence := v_next_seq;
    NEW.created_at := COALESCE(NEW.created_at, clock_timestamp());
    RETURN NEW;
END;
$$;

CREATE TRIGGER bid_guard_and_sequence
BEFORE INSERT ON auction.bid
FOR EACH ROW EXECUTE FUNCTION auction.guard_and_sequence_bid();

-- Update current pot / next minimum / anti-sniping atomically after an accepted Bid.
CREATE OR REPLACE FUNCTION auction.apply_accepted_bid()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    v_now timestamptz := clock_timestamp();
    v_new_end timestamptz;
BEGIN
    IF NEW.status NOT IN ('VALID','WINNING') THEN
        RETURN NEW;
    END IF;

    IF NEW.bid_type = 'INSTANT_CLOSE' THEN
        UPDATE auction.auction
           SET current_amount_clp = NEW.amount_clp,
               next_actionable_amount_clp = platform.next_minimum_bid(NEW.amount_clp),
               status = 'CLOSED',
               winner_user_id = NEW.bidder_user_id,
               winning_bid_id = NEW.id,
               close_reason = 'INSTANT_CLOSE',
               lock_version = lock_version + 1
         WHERE id = NEW.auction_id;
        UPDATE auction.bid SET status = 'WINNING' WHERE id = NEW.id AND status <> 'WINNING';
        RETURN NEW;
    END IF;

    SELECT CASE
             WHEN effective_end_at - v_now <= interval '2 minutes' THEN v_now + interval '2 minutes'
             ELSE effective_end_at
           END
      INTO v_new_end
      FROM auction.auction
     WHERE id = NEW.auction_id;

    UPDATE auction.auction
       SET current_amount_clp = NEW.amount_clp,
           next_actionable_amount_clp = platform.next_minimum_bid(NEW.amount_clp),
           effective_end_at = v_new_end,
           lock_version = lock_version + 1
     WHERE id = NEW.auction_id;

    RETURN NEW;
END;
$$;

CREATE TRIGGER bid_apply_after_insert
AFTER INSERT ON auction.bid
FOR EACH ROW EXECUTE FUNCTION auction.apply_accepted_bid();

-- --------------------------------------------------------------------------
-- 8. Appointment / presence / sessions / extensions
-- --------------------------------------------------------------------------
CREATE TABLE appointment.appointment (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    auction_id uuid NOT NULL UNIQUE REFERENCES auction.auction(id) ON DELETE RESTRICT,
    host_user_id uuid NOT NULL REFERENCES iam.app_user(id) ON DELETE RESTRICT,
    bidder_user_id uuid NOT NULL REFERENCES iam.app_user(id) ON DELETE RESTRICT,
    modality platform.modality NOT NULL,
    duration_minutes smallint NOT NULL,
    agreed_amount_clp bigint NOT NULL CHECK (agreed_amount_clp > 0 AND agreed_amount_clp % 5000 = 0),
    status platform.appointment_status NOT NULL DEFAULT 'AWAITING_CONFIRMATION',
    winner_confirmed_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    lock_version integer NOT NULL DEFAULT 0 CHECK (lock_version >= 0),
    CONSTRAINT appointment_duration_ck CHECK (platform.valid_base_duration(modality, duration_minutes)),
    CONSTRAINT appointment_distinct_parties_ck CHECK (host_user_id <> bidder_user_id)
);

ALTER TABLE geo.operational_location_event
    ADD CONSTRAINT operational_location_appointment_fk
    FOREIGN KEY (appointment_id) REFERENCES appointment.appointment(id) ON DELETE SET NULL;

CREATE TABLE appointment.meeting_place (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    appointment_id uuid NOT NULL UNIQUE REFERENCES appointment.appointment(id) ON DELETE CASCADE,
    place_type platform.place_type NOT NULL,
    public_label text,
    private_address_ciphertext bytea,
    exact_point geography(Point,4326),
    revealed_to_bidder_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT meeting_place_private_ck CHECK (
        (place_type = 'HOST_PRIVATE' AND private_address_ciphertext IS NOT NULL)
        OR place_type = 'PUBLIC'
    )
);

CREATE TABLE appointment.arrival_check_in (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    appointment_id uuid NOT NULL REFERENCES appointment.appointment(id) ON DELETE CASCADE,
    user_id uuid NOT NULL REFERENCES iam.app_user(id) ON DELETE RESTRICT,
    status platform.arrival_status NOT NULL,
    distance_m integer CHECK (distance_m IS NULL OR distance_m >= 0),
    checked_at timestamptz NOT NULL DEFAULT now(),
    location_event_id bigint REFERENCES geo.operational_location_event(id) ON DELETE SET NULL,
    evidence_hash text
);

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
