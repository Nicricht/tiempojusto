BEGIN;

CREATE TABLE IF NOT EXISTS auction.reservation_replacement (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    auction_id uuid NOT NULL REFERENCES auction.auction(id) ON DELETE RESTRICT,
    bidder_user_id uuid NOT NULL REFERENCES iam.app_user(id) ON DELETE RESTRICT,
    prior_reservation_id uuid NOT NULL REFERENCES auction.funds_reservation(id) ON DELETE RESTRICT,
    replacement_reservation_id uuid NOT NULL REFERENCES auction.funds_reservation(id) ON DELETE RESTRICT,
    target_amount_clp bigint NOT NULL CHECK (target_amount_clp > 0 AND target_amount_clp % 5000 = 0),
    state varchar(24) NOT NULL DEFAULT 'RELEASE_PENDING'
        CHECK (state IN ('RELEASE_PENDING','RELEASED')),
    idempotency_key text NOT NULL UNIQUE,
    attempt_count integer NOT NULL DEFAULT 0 CHECK (attempt_count >= 0),
    last_attempt_at timestamptz,
    last_error_code varchar(100),
    released_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT reservation_replacement_distinct_ck CHECK (prior_reservation_id <> replacement_reservation_id),
    CONSTRAINT reservation_replacement_released_ck CHECK (
        (state = 'RELEASED' AND released_at IS NOT NULL)
        OR (state = 'RELEASE_PENDING' AND released_at IS NULL)
    )
);

CREATE UNIQUE INDEX IF NOT EXISTS reservation_replacement_new_reservation_uidx
    ON auction.reservation_replacement(replacement_reservation_id);

CREATE INDEX IF NOT EXISTS reservation_replacement_pending_idx
    ON auction.reservation_replacement(created_at)
    WHERE state = 'RELEASE_PENDING';

COMMENT ON TABLE auction.reservation_replacement IS
'Tracks deferred release of a superseded Bid funds reservation. The replacement reservation must exist before the accepted Bid is committed; the prior reservation is released only after commit or by retry.';

COMMIT;
