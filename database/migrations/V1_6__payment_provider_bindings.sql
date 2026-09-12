-- TiempoJusto V1.6 - payment provider bindings
-- Stores only provider object references and monetary state.
-- Never store PAN, CVV, raw card payloads or provider access tokens here.

CREATE TABLE finance.provider_reservation_binding (
    internal_id uuid PRIMARY KEY,
    payer_user_id uuid NOT NULL REFERENCES iam.app_user(id) ON DELETE RESTRICT,
    provider_code varchar(40) NOT NULL,
    provider_payment_id varchar(160) NOT NULL,
    authorized_amount_clp bigint NOT NULL CHECK (authorized_amount_clp > 0),
    remaining_reserved_clp bigint NOT NULL CHECK (remaining_reserved_clp >= 0),
    reservation_status varchar(32) NOT NULL CHECK (reservation_status IN ('RESERVED','PARTIALLY_CAPTURED','CAPTURED','RELEASED')),
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (provider_code, provider_payment_id),
    CHECK (remaining_reserved_clp <= authorized_amount_clp)
);

CREATE TABLE finance.provider_capture_binding (
    internal_id uuid PRIMARY KEY,
    reservation_id uuid NOT NULL REFERENCES finance.provider_reservation_binding(internal_id) ON DELETE RESTRICT,
    payer_user_id uuid NOT NULL REFERENCES iam.app_user(id) ON DELETE RESTRICT,
    provider_code varchar(40) NOT NULL,
    provider_payment_id varchar(160) NOT NULL,
    amount_clp bigint NOT NULL CHECK (amount_clp > 0),
    refundable_remaining_clp bigint NOT NULL CHECK (refundable_remaining_clp >= 0),
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (reservation_id),
    CHECK (refundable_remaining_clp <= amount_clp)
);

CREATE TABLE finance.provider_refund_binding (
    internal_id uuid PRIMARY KEY,
    capture_id uuid NOT NULL REFERENCES finance.provider_capture_binding(internal_id) ON DELETE RESTRICT,
    payer_user_id uuid NOT NULL REFERENCES iam.app_user(id) ON DELETE RESTRICT,
    provider_code varchar(40) NOT NULL,
    provider_refund_id varchar(160) NOT NULL,
    amount_clp bigint NOT NULL CHECK (amount_clp > 0),
    created_at timestamptz NOT NULL,
    UNIQUE (provider_code, provider_refund_id)
);

CREATE INDEX provider_reservation_payer_idx
    ON finance.provider_reservation_binding (payer_user_id, created_at DESC);
CREATE INDEX provider_capture_payer_idx
    ON finance.provider_capture_binding (payer_user_id, created_at DESC);
CREATE INDEX provider_refund_capture_idx
    ON finance.provider_refund_binding (capture_id, created_at DESC);

COMMENT ON TABLE finance.provider_reservation_binding IS
'P2.3 provider-neutral reservation mapping. Contains no PAN/CVV/card token/access token.';
COMMENT ON TABLE finance.provider_capture_binding IS
'P2.3 provider capture mapping used to reconcile provider money with TiempoJusto internal UUIDs.';
COMMENT ON TABLE finance.provider_refund_binding IS
'P2.3 provider refund mapping. Provider refund identifiers are reconciliation references only.';