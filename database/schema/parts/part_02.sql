
CREATE UNIQUE INDEX proposal_one_active_uidx
ON market.proposal(bidder_user_id, host_profile_id, modality, duration_minutes)
WHERE status = 'ACTIVE';

CREATE TRIGGER proposal_set_updated_at
BEFORE UPDATE ON market.proposal
FOR EACH ROW EXECUTE FUNCTION platform.set_updated_at();

CREATE TABLE market.proposal_metric_snapshot (
    id bigserial PRIMARY KEY,
    host_profile_id uuid NOT NULL REFERENCES profile.host_profile(id) ON DELETE CASCADE,
    modality platform.modality NOT NULL,
    duration_minutes smallint NOT NULL,
    max_clp bigint,
    avg_clp bigint,
    min_clp bigint,
    active_interested_count integer NOT NULL DEFAULT 0 CHECK (active_interested_count >= 0),
    calculated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT proposal_metric_duration_ck CHECK (platform.valid_base_duration(modality, duration_minutes)),
    CONSTRAINT proposal_metric_values_ck CHECK (
        (max_clp IS NULL OR max_clp >= 0) AND
        (avg_clp IS NULL OR avg_clp >= 0) AND
        (min_clp IS NULL OR min_clp >= 0) AND
        (max_clp IS NULL OR min_clp IS NULL OR max_clp >= min_clp)
    )
);

CREATE TABLE market.now_availability (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    host_profile_id uuid NOT NULL REFERENCES profile.host_profile(id) ON DELETE CASCADE,
    modality platform.modality NOT NULL,
    duration_minutes smallint NOT NULL,
    place_type platform.place_type,
    approx_location_id uuid REFERENCES geo.approximate_location(id),
    status platform.availability_status NOT NULL DEFAULT 'ACTIVE',
    conditions_text text,
    instant_close_enabled boolean NOT NULL DEFAULT false,
    activated_at timestamptz NOT NULL DEFAULT now(),
    ended_at timestamptz,
    lock_version integer NOT NULL DEFAULT 0 CHECK (lock_version >= 0),
    CONSTRAINT now_availability_duration_ck CHECK (platform.valid_base_duration(modality, duration_minutes)),
    CONSTRAINT now_availability_place_ck CHECK (
        (modality = 'IN_PERSON' AND place_type IS NOT NULL)
        OR (modality = 'ONLINE' AND place_type IS NULL)
    ),
    CONSTRAINT now_availability_end_ck CHECK (ended_at IS NULL OR ended_at >= activated_at)
);

CREATE TABLE market.meta_now (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    availability_id uuid NOT NULL UNIQUE REFERENCES market.now_availability(id) ON DELETE CASCADE,
    target_amount_clp bigint NOT NULL,
    window_minutes smallint NOT NULL,
    status platform.meta_status NOT NULL DEFAULT 'ACTIVE',
    highest_compatible_proposal_id uuid REFERENCES market.proposal(id),
    progress_percent numeric(5,2) NOT NULL DEFAULT 0,
    deadline_at timestamptz NOT NULL,
    reached_at timestamptz,
    CONSTRAINT meta_target_ck CHECK (target_amount_clp >= 10000 AND target_amount_clp % 5000 = 0),
    CONSTRAINT meta_window_ck CHECK (window_minutes IN (30,60,120,240)),
    CONSTRAINT meta_progress_ck CHECK (progress_percent BETWEEN 0 AND 100),
    CONSTRAINT meta_reached_ck CHECK (status <> 'REACHED' OR (reached_at IS NOT NULL AND progress_percent = 100))
);

-- --------------------------------------------------------------------------
-- 7. Auction / funding
-- --------------------------------------------------------------------------
CREATE TABLE auction.funds_reservation (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id uuid NOT NULL REFERENCES iam.app_user(id) ON DELETE RESTRICT,
    provider_code varchar(50) NOT NULL,
    provider_reference varchar(160) NOT NULL UNIQUE,
    amount_clp bigint NOT NULL CHECK (amount_clp > 0),
    status platform.funds_status NOT NULL,
    purpose_type varchar(40) NOT NULL,
    purpose_id uuid,
    reserved_at timestamptz,
    expires_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT funds_reserved_ck CHECK (status <> 'RESERVED' OR reserved_at IS NOT NULL),
    CONSTRAINT funds_expiry_ck CHECK (expires_at IS NULL OR expires_at > created_at)
);

CREATE TABLE auction.auction (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    host_user_id uuid NOT NULL REFERENCES iam.app_user(id) ON DELETE RESTRICT,
    availability_id uuid REFERENCES market.now_availability(id) ON DELETE SET NULL,
    meta_now_id uuid REFERENCES market.meta_now(id) ON DELETE SET NULL,
    modality platform.modality NOT NULL,
    duration_minutes smallint NOT NULL,
    opening_amount_clp bigint NOT NULL,
    current_amount_clp bigint NOT NULL,
    next_actionable_amount_clp bigint NOT NULL,
    instant_close_amount_clp bigint,
    status platform.auction_status NOT NULL DEFAULT 'OPEN',
    started_at timestamptz NOT NULL,
    scheduled_end_at timestamptz NOT NULL,
    effective_end_at timestamptz NOT NULL,
    winner_user_id uuid REFERENCES iam.app_user(id) ON DELETE RESTRICT,
    winning_bid_id uuid,
    close_reason varchar(40),
    lock_version integer NOT NULL DEFAULT 0 CHECK (lock_version >= 0),
    CONSTRAINT auction_duration_ck CHECK (platform.valid_base_duration(modality, duration_minutes)),
    CONSTRAINT auction_opening_amount_ck CHECK (opening_amount_clp >= 0 AND opening_amount_clp % 5000 = 0),
    CONSTRAINT auction_current_amount_ck CHECK (current_amount_clp >= opening_amount_clp AND current_amount_clp % 5000 = 0),
    CONSTRAINT auction_next_amount_ck CHECK (next_actionable_amount_clp >= current_amount_clp + 5000 AND next_actionable_amount_clp % 5000 = 0),
    CONSTRAINT auction_instant_close_ck CHECK (instant_close_amount_clp IS NULL OR (instant_close_amount_clp % 5000 = 0 AND (status <> 'OPEN' OR instant_close_amount_clp > current_amount_clp))),
    CONSTRAINT auction_15m_ck CHECK (scheduled_end_at = started_at + interval '15 minutes'),
    CONSTRAINT auction_effective_end_ck CHECK (effective_end_at >= scheduled_end_at),
    CONSTRAINT auction_winner_state_ck CHECK (
        (winner_user_id IS NULL AND winning_bid_id IS NULL)
        OR (status = 'CLOSED' AND winner_user_id IS NOT NULL AND winning_bid_id IS NOT NULL)
    )
);

CREATE TABLE auction.auction_participant (
    auction_id uuid NOT NULL REFERENCES auction.auction(id) ON DELETE CASCADE,
    bidder_user_id uuid NOT NULL REFERENCES iam.app_user(id) ON DELETE RESTRICT,
    eligibility_status platform.eligibility_status NOT NULL,
    joined_at timestamptz NOT NULL DEFAULT now(),
    last_active_at timestamptz,
    rank_at_close smallint CHECK (rank_at_close IS NULL OR rank_at_close > 0),
    confirmation_deadline timestamptz,
    confirmation_status platform.confirmation_status,
    PRIMARY KEY (auction_id, bidder_user_id),
    CONSTRAINT participant_confirmation_ck CHECK (
        confirmation_status IS NULL OR confirmation_deadline IS NOT NULL
    )
);

CREATE TABLE auction.bid (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    auction_id uuid NOT NULL REFERENCES auction.auction(id) ON DELETE CASCADE,
    bidder_user_id uuid NOT NULL REFERENCES iam.app_user(id) ON DELETE RESTRICT,
    amount_clp bigint NOT NULL CHECK (amount_clp > 0 AND amount_clp % 5000 = 0),
    bid_type platform.bid_type NOT NULL DEFAULT 'NORMAL',
    status platform.bid_status NOT NULL,
    funds_reservation_id uuid REFERENCES auction.funds_reservation(id) ON DELETE RESTRICT,
    server_sequence bigint NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    idempotency_key text NOT NULL UNIQUE,
    UNIQUE (auction_id, server_sequence)
);

ALTER TABLE auction.auction
    ADD CONSTRAINT auction_winning_bid_fk
    FOREIGN KEY (winning_bid_id) REFERENCES auction.bid(id)
    DEFERRABLE INITIALLY DEFERRED;

-- Physical winner integrity: winner and winning Bid must belong to the same Auction/user.
CREATE OR REPLACE FUNCTION auction.assert_winner_integrity()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE v_bid auction.bid%ROWTYPE;
BEGIN
    IF NEW.status = 'CLOSED' AND NEW.winning_bid_id IS NOT NULL THEN
        SELECT * INTO v_bid FROM auction.bid WHERE id = NEW.winning_bid_id;
        IF NOT FOUND OR v_bid.auction_id <> NEW.id OR v_bid.bidder_user_id <> NEW.winner_user_id OR v_bid.status <> 'WINNING' THEN
            RAISE EXCEPTION 'winning_bid_id/winner_user_id do not match the closed auction';
        END IF;
    END IF;
    RETURN NEW;
END;
$$;
