    date_of_birth date,
    verified_at timestamptz,
    expires_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT identity_verification_dates_ck CHECK (expires_at IS NULL OR verified_at IS NULL OR expires_at > verified_at),
    CONSTRAINT identity_verified_ck CHECK (status <> 'VERIFIED' OR (verified_adult = true AND verified_at IS NOT NULL))
);

COMMENT ON TABLE iam.identity_verification IS
'KYC result/reference only. Do not store raw KYC documents or raw biometric payloads. Market-role eligibility attributes not frozen in ER V1.0 must not be inferred from photos.';

CREATE TABLE iam.device (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id uuid NOT NULL REFERENCES iam.app_user(id) ON DELETE CASCADE,
    device_fingerprint_hash text,
    platform varchar(32),
    push_token_ciphertext bytea,
    last_seen_at timestamptz,
    revoked_at timestamptz
);

CREATE TABLE iam.user_block (
    blocker_user_id uuid NOT NULL REFERENCES iam.app_user(id) ON DELETE CASCADE,
    blocked_user_id uuid NOT NULL REFERENCES iam.app_user(id) ON DELETE CASCADE,
    created_at timestamptz NOT NULL DEFAULT now(),
    reason_code varchar(40),
    PRIMARY KEY (blocker_user_id, blocked_user_id),
    CONSTRAINT user_block_not_self_ck CHECK (blocker_user_id <> blocked_user_id)
);

-- --------------------------------------------------------------------------
-- 4. Profile
-- --------------------------------------------------------------------------
CREATE TABLE profile.host_profile (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id uuid NOT NULL UNIQUE REFERENCES iam.app_user(id) ON DELETE CASCADE,
    description text,
    supports_in_person boolean NOT NULL DEFAULT false,
    supports_online boolean NOT NULL DEFAULT false,
    map_visible boolean NOT NULL DEFAULT true,
    profile_status platform.profile_status NOT NULL DEFAULT 'DRAFT',
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT host_profile_modality_ck CHECK (profile_status = 'DRAFT' OR supports_in_person OR supports_online)
);

CREATE TRIGGER host_profile_set_updated_at
BEFORE UPDATE ON profile.host_profile
FOR EACH ROW EXECUTE FUNCTION platform.set_updated_at();

CREATE OR REPLACE FUNCTION profile.guard_host_role()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE v_role platform.user_role;
BEGIN
    SELECT role INTO v_role FROM iam.app_user WHERE id = NEW.user_id;
    IF v_role IS DISTINCT FROM 'HOST'::platform.user_role THEN
        RAISE EXCEPTION 'host_profile requires app_user.role=HOST';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER host_profile_role_guard
BEFORE INSERT OR UPDATE OF user_id ON profile.host_profile
FOR EACH ROW EXECUTE FUNCTION profile.guard_host_role();

CREATE TABLE profile.profile_media (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    profile_id uuid NOT NULL REFERENCES profile.host_profile(id) ON DELETE CASCADE,
    media_type platform.media_type NOT NULL,
    storage_key text NOT NULL,
    is_primary boolean NOT NULL DEFAULT false,
    sort_order smallint NOT NULL DEFAULT 0 CHECK (sort_order >= 0),
    duration_seconds smallint,
    moderation_status varchar(30) NOT NULL DEFAULT 'ACTIVE',
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT profile_media_video_duration_ck CHECK (
        (media_type = 'VIDEO' AND duration_seconds BETWEEN 15 AND 30)
        OR (media_type = 'PHOTO' AND duration_seconds IS NULL)
    )
);

CREATE UNIQUE INDEX profile_one_primary_photo_uidx
ON profile.profile_media(profile_id)
WHERE is_primary = true AND media_type = 'PHOTO';

CREATE TABLE profile.profile_attribute (
    profile_id uuid NOT NULL REFERENCES profile.host_profile(id) ON DELETE CASCADE,
    attribute_key varchar(50) NOT NULL,
    attribute_value varchar(80) NOT NULL,
    declared_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (profile_id, attribute_key)
);

COMMENT ON TABLE profile.profile_attribute IS
'Only optional self-declared attributes allowed by product policy. No CV/beauty inference from media.';

-- --------------------------------------------------------------------------
-- 5. Geo
-- --------------------------------------------------------------------------
CREATE TABLE geo.approximate_location (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    profile_id uuid NOT NULL REFERENCES profile.host_profile(id) ON DELETE CASCADE,
    cell_code varchar(64) NOT NULL,
    centroid geography(Point,4326),
    precision_level smallint NOT NULL CHECK (precision_level >= 0),
    valid_from timestamptz NOT NULL DEFAULT now(),
    valid_until timestamptz,
    CONSTRAINT approximate_location_window_ck CHECK (valid_until IS NULL OR valid_until > valid_from)
);

CREATE TABLE geo.location_visibility (
    user_id uuid PRIMARY KEY REFERENCES iam.app_user(id) ON DELETE CASCADE,
    map_visible boolean NOT NULL,
    hidden_until timestamptz,
    updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE TRIGGER location_visibility_set_updated_at
BEFORE UPDATE ON geo.location_visibility
FOR EACH ROW EXECUTE FUNCTION platform.set_updated_at();

-- appointment_id FK is added after appointment.appointment exists.
CREATE TABLE geo.operational_location_event (
    id bigserial PRIMARY KEY,
    user_id uuid NOT NULL REFERENCES iam.app_user(id) ON DELETE CASCADE,
    appointment_id uuid,
    point geography(Point,4326) NOT NULL,
    accuracy_m real CHECK (accuracy_m IS NULL OR accuracy_m >= 0),
    event_type varchar(40) NOT NULL,
    captured_at timestamptz NOT NULL DEFAULT now(),
    purge_after timestamptz NOT NULL,
    preservation_hold boolean NOT NULL DEFAULT false,
    CONSTRAINT operational_geo_ttl_ck CHECK (purge_after > captured_at)
);

COMMENT ON TABLE geo.operational_location_event IS
'Exact operational GPS only. V1.7 default concept is about 24h retention unless preservation_hold is valid. Never expose as public live tracking.';

-- --------------------------------------------------------------------------
-- 6. Market: Proposal, metrics, Disponible Ahora, Meta Ahora
-- --------------------------------------------------------------------------
CREATE TABLE market.proposal (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    bidder_user_id uuid NOT NULL REFERENCES iam.app_user(id) ON DELETE CASCADE,
    host_profile_id uuid NOT NULL REFERENCES profile.host_profile(id) ON DELETE CASCADE,
    modality platform.modality NOT NULL,
    duration_minutes smallint NOT NULL,
    amount_clp bigint NOT NULL,
    status platform.proposal_status NOT NULL DEFAULT 'ACTIVE',
    valid_until timestamptz NOT NULL,
    withdrawn_at timestamptz,
    cooldown_until timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    lock_version integer NOT NULL DEFAULT 0 CHECK (lock_version >= 0),
    CONSTRAINT proposal_duration_ck CHECK (platform.valid_base_duration(modality, duration_minutes)),
    CONSTRAINT proposal_amount_ck CHECK (amount_clp >= 10000 AND amount_clp % 5000 = 0),
    CONSTRAINT proposal_validity_ck CHECK (valid_until > created_at),
    CONSTRAINT proposal_withdrawal_ck CHECK (
        (status = 'WITHDRAWN' AND withdrawn_at IS NOT NULL)
        OR status <> 'WITHDRAWN'
    ),
    CONSTRAINT proposal_cooldown_ck CHECK (cooldown_until IS NULL OR withdrawn_at IS NULL OR cooldown_until >= withdrawn_at + interval '24 hours')
);

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
