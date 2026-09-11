-- TIEMPOJUSTO P1.3 GEO / ROUTING / ETA V1
-- Source: Documento Maestro V1.7.
-- Purpose: materialize approximate public location, exact operational GPS retention,
-- provider-neutral route estimates and the <=30 minute in-person eligibility guard.
-- Real routing provider selection remains intentionally unresolved.

BEGIN;

CREATE OR REPLACE FUNCTION geo.guard_operational_location_retention()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    NEW.captured_at := COALESCE(NEW.captured_at, clock_timestamp());
    IF NEW.purge_after IS NULL THEN
        NEW.purge_after := NEW.captured_at + interval '24 hours';
    END IF;

    IF NEW.purge_after <= NEW.captured_at THEN
        RAISE EXCEPTION 'operational location purge_after must be after captured_at';
    END IF;

    IF COALESCE(NEW.preservation_hold, false) = false
       AND NEW.purge_after > NEW.captured_at + interval '24 hours' THEN
        RAISE EXCEPTION 'exact operational GPS cannot be retained over 24h without preservation_hold';
    END IF;

    RETURN NEW;
END;
$$;

DROP TRIGGER IF EXISTS operational_location_retention_guard ON geo.operational_location_event;
CREATE TRIGGER operational_location_retention_guard
BEFORE INSERT OR UPDATE OF captured_at, purge_after, preservation_hold
ON geo.operational_location_event
FOR EACH ROW EXECUTE FUNCTION geo.guard_operational_location_retention();

CREATE OR REPLACE FUNCTION geo.purge_expired_operational_locations(p_now timestamptz DEFAULT clock_timestamp())
RETURNS bigint
LANGUAGE plpgsql
AS $$
DECLARE v_count bigint;
BEGIN
    DELETE FROM geo.operational_location_event
     WHERE preservation_hold = false
       AND purge_after <= p_now;
    GET DIAGNOSTICS v_count = ROW_COUNT;
    RETURN v_count;
END;
$$;

COMMENT ON FUNCTION geo.purge_expired_operational_locations(timestamptz) IS
'Deletes exact operational GPS after its TTL. Schedule as a backend/database job; preservation holds are excluded.';

CREATE OR REPLACE VIEW geo.public_profile_location_v AS
SELECT DISTINCT ON (al.profile_id)
       al.profile_id,
       al.cell_code,
       al.centroid,
       al.precision_level,
       al.valid_from,
       al.valid_until
  FROM geo.approximate_location al
  JOIN profile.host_profile hp ON hp.id = al.profile_id
  LEFT JOIN geo.location_visibility lv ON lv.user_id = hp.user_id
 WHERE hp.map_visible = true
   AND COALESCE(lv.map_visible, true) = true
   AND (lv.hidden_until IS NULL OR lv.hidden_until <= clock_timestamp())
   AND (al.valid_until IS NULL OR al.valid_until > clock_timestamp())
 ORDER BY al.profile_id, al.valid_from DESC;

COMMENT ON VIEW geo.public_profile_location_v IS
'Public/semipublic map projection only. It intentionally exposes approximate cells/centroids and never operational exact GPS.';

CREATE OR REPLACE VIEW appointment.meeting_place_public_v AS
SELECT id, appointment_id, place_type, public_label, revealed_to_bidder_at, created_at
  FROM appointment.meeting_place;

COMMENT ON VIEW appointment.meeting_place_public_v IS
'Safe projection for UI/read models. Does not expose private_address_ciphertext or exact_point.';

CREATE TABLE IF NOT EXISTS geo.route_estimate_snapshot (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    auction_id uuid NOT NULL REFERENCES auction.auction(id) ON DELETE CASCADE,
    bidder_user_id uuid NOT NULL REFERENCES iam.app_user(id) ON DELETE RESTRICT,
    origin_location_event_id bigint REFERENCES geo.operational_location_event(id) ON DELETE SET NULL,
    destination_fingerprint text NOT NULL,
    travel_mode varchar(20) NOT NULL,
    distance_m bigint NOT NULL CHECK (distance_m >= 0),
    duration_seconds integer NOT NULL CHECK (duration_seconds >= 0),
    provider_code varchar(60) NOT NULL,
    provider_reference varchar(200),
    estimated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    valid_until timestamptz NOT NULL,
    eligible_under_30m boolean GENERATED ALWAYS AS (duration_seconds <= 1800) STORED,
    CONSTRAINT route_estimate_window_ck CHECK (valid_until > estimated_at),
    CONSTRAINT route_estimate_mode_ck CHECK (travel_mode IN ('DRIVING','WALKING','CYCLING','TRANSIT','UNKNOWN')),
    CONSTRAINT route_estimate_destination_fingerprint_ck CHECK (length(destination_fingerprint) >= 16),
    UNIQUE (id, auction_id, bidder_user_id)
);

COMMENT ON TABLE geo.route_estimate_snapshot IS
'Aggregated ETA evidence for in-person Auction eligibility. Stores duration/distance/provider refs, not route geometry or a continuous location history.';

CREATE INDEX IF NOT EXISTS route_estimate_auction_bidder_idx
ON geo.route_estimate_snapshot(auction_id, bidder_user_id, estimated_at DESC);

CREATE INDEX IF NOT EXISTS route_estimate_valid_idx
ON geo.route_estimate_snapshot(valid_until)
WHERE eligible_under_30m = true;

ALTER TABLE auction.auction_participant
    ADD COLUMN IF NOT EXISTS route_estimate_id uuid;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
         WHERE conname = 'participant_route_estimate_fk'
           AND conrelid = 'auction.auction_participant'::regclass
    ) THEN
        ALTER TABLE auction.auction_participant
            ADD CONSTRAINT participant_route_estimate_fk
            FOREIGN KEY (route_estimate_id)
            REFERENCES geo.route_estimate_snapshot(id)
            ON DELETE SET NULL;
    END IF;
END;
$$;

CREATE OR REPLACE FUNCTION geo.guard_in_person_eta_eligibility()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    v_modality platform.modality;
    v_route geo.route_estimate_snapshot%ROWTYPE;
BEGIN
    IF NEW.eligibility_status <> 'ELIGIBLE'::platform.eligibility_status THEN
        RETURN NEW;
    END IF;

    SELECT modality INTO v_modality
      FROM auction.auction
     WHERE id = NEW.auction_id;

    IF v_modality = 'IN_PERSON'::platform.modality THEN
        IF NEW.route_estimate_id IS NULL THEN
            RAISE EXCEPTION 'in-person ELIGIBLE participant requires route_estimate_id';
        END IF;

        SELECT * INTO v_route
          FROM geo.route_estimate_snapshot
         WHERE id = NEW.route_estimate_id;

        IF NOT FOUND
           OR v_route.auction_id <> NEW.auction_id
           OR v_route.bidder_user_id <> NEW.bidder_user_id THEN
            RAISE EXCEPTION 'route estimate does not belong to participant/auction';
        END IF;

        IF v_route.duration_seconds > 1800 THEN
            RAISE EXCEPTION 'in-person participant ETA exceeds 30 minutes';
        END IF;

        IF v_route.valid_until < COALESCE(NEW.joined_at, clock_timestamp()) THEN
            RAISE EXCEPTION 'route estimate was stale when participant joined';
        END IF;
    END IF;

    RETURN NEW;
END;
$$;

DROP TRIGGER IF EXISTS participant_eta_eligibility_guard ON auction.auction_participant;
CREATE TRIGGER participant_eta_eligibility_guard
BEFORE INSERT OR UPDATE OF eligibility_status, route_estimate_id, joined_at
ON auction.auction_participant
FOR EACH ROW EXECUTE FUNCTION geo.guard_in_person_eta_eligibility();

CREATE OR REPLACE FUNCTION geo.find_public_profiles_within(
    p_center geography,
    p_radius_m integer,
    p_limit integer DEFAULT 50)
RETURNS TABLE (
    profile_id uuid,
    cell_code varchar,
    distance_m double precision
)
LANGUAGE sql
STABLE
AS $$
    SELECT ppl.profile_id,
           ppl.cell_code,
           ST_Distance(ppl.centroid, p_center) AS distance_m
      FROM geo.public_profile_location_v ppl
     WHERE ppl.centroid IS NOT NULL
       AND p_radius_m > 0
       AND ST_DWithin(ppl.centroid, p_center, p_radius_m)
     ORDER BY ST_Distance(ppl.centroid, p_center)
     LIMIT LEAST(GREATEST(p_limit, 1), 100);
$$;

COMMENT ON FUNCTION geo.find_public_profiles_within(geography, integer, integer) IS
'Approximate Discovery helper. Uses public centroids only and must not be repurposed for exact tracking.';

COMMIT;
