-- TiempoJusto V1.2 - WebRTC / Media hardening
-- Source: Documento Maestro V1.7, P1.4 and Online/Live frozen rules.
-- This migration does NOT select a real WebRTC/TURN provider.

BEGIN;

ALTER TABLE media.video_room
    ADD COLUMN IF NOT EXISTS provider_region varchar(64),
    ADD COLUMN IF NOT EXISTS persistent_recording_enabled boolean NOT NULL DEFAULT false;

ALTER TABLE media.video_room
    ADD CONSTRAINT video_private_recording_disabled_ck
    CHECK (persistent_recording_enabled = false),
    ADD CONSTRAINT video_paid_acceptance_30s_ck
    CHECK (
        paid_acceptance_deadline IS NULL OR
        (free_online_end IS NOT NULL AND paid_acceptance_deadline = free_online_end + interval '30 seconds')
    );

COMMENT ON COLUMN media.video_room.persistent_recording_enabled IS
'Private Online calls are not recorded by default in V1.7. This physical V1.2 hardens that MVP rule to false.';

CREATE TABLE IF NOT EXISTS media.video_participant_state (
    video_room_id uuid NOT NULL REFERENCES media.video_room(id) ON DELETE CASCADE,
    user_id uuid NOT NULL REFERENCES iam.app_user(id) ON DELETE RESTRICT,
    participant_role varchar(16) NOT NULL CHECK (participant_role IN ('HOST','BIDDER')),
    joined_at timestamptz,
    left_at timestamptz,
    camera_valid boolean NOT NULL DEFAULT false,
    media_flowing boolean NOT NULL DEFAULT false,
    audio_muted boolean NOT NULL DEFAULT false,
    last_heartbeat_at timestamptz,
    last_valid_media_at timestamptz,
    updated_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (video_room_id, user_id),
    CONSTRAINT video_participant_time_ck CHECK (left_at IS NULL OR joined_at IS NULL OR left_at >= joined_at),
    CONSTRAINT video_participant_valid_media_ck CHECK (
        last_valid_media_at IS NULL OR
        (joined_at IS NOT NULL AND last_heartbeat_at IS NOT NULL AND last_valid_media_at <= last_heartbeat_at)
    )
);

CREATE UNIQUE INDEX IF NOT EXISTS video_room_one_role_uidx
ON media.video_participant_state(video_room_id, participant_role);

CREATE INDEX IF NOT EXISTS video_participant_heartbeat_idx
ON media.video_participant_state(video_room_id, last_heartbeat_at DESC);

COMMENT ON TABLE media.video_participant_state IS
'Current technical media state only. Do not store audiovisual content. audio_muted alone is not an automatic billing-pause signal.';

CREATE OR REPLACE FUNCTION media.guard_online_paid_media_ready()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE v_ready_count integer;
BEGIN
    IF NEW.session_mode = 'ONLINE'
       AND NEW.status = 'PAID_ACTIVE'
       AND (TG_OP = 'INSERT' OR OLD.status IS DISTINCT FROM NEW.status) THEN
        SELECT count(*) INTO v_ready_count
          FROM media.video_room vr
          JOIN media.video_participant_state ps ON ps.video_room_id = vr.id
         WHERE vr.appointment_id = NEW.appointment_id
           AND ps.joined_at IS NOT NULL
           AND ps.left_at IS NULL
           AND ps.camera_valid = true
           AND ps.media_flowing = true
           AND ps.participant_role IN ('HOST','BIDDER');
        IF v_ready_count <> 2 THEN
            RAISE EXCEPTION 'Online PAID_ACTIVE requires HOST and BIDDER with valid camera/media';
        END IF;
    END IF;
    RETURN NEW;
END;
$$;

DROP TRIGGER IF EXISTS online_paid_media_ready_guard ON appointment.appointment_session;
CREATE TRIGGER online_paid_media_ready_guard
BEFORE INSERT OR UPDATE OF status ON appointment.appointment_session
FOR EACH ROW EXECUTE FUNCTION media.guard_online_paid_media_ready();

ALTER TABLE media.live_session
    ADD COLUMN IF NOT EXISTS provider_region varchar(64),
    ADD COLUMN IF NOT EXISTS media_lost_at timestamptz,
    ADD COLUMN IF NOT EXISTS reconnect_deadline timestamptz,
    ADD COLUMN IF NOT EXISTS persistent_recording_enabled boolean NOT NULL DEFAULT false;

ALTER TABLE media.live_session
    ADD CONSTRAINT live_reconnect_2m_ck
    CHECK (reconnect_deadline IS NULL OR (media_lost_at IS NOT NULL AND reconnect_deadline = media_lost_at + interval '2 minutes')),
    ADD CONSTRAINT live_persistent_recording_disabled_ck
    CHECK (persistent_recording_enabled = false);

CREATE UNIQUE INDEX IF NOT EXISTS live_provider_room_ref_uidx
ON media.live_session(provider_room_ref)
WHERE provider_room_ref IS NOT NULL;

COMMENT ON COLUMN media.live_session.reconnect_deadline IS
'Live has a 2-minute reconnect window. Auction remains independent and continues during media outage.';
COMMENT ON COLUMN media.live_session.persistent_recording_enabled IS
'V1.7 has no public replay and does not keep a complete permanent Live recording by default.';

CREATE TABLE IF NOT EXISTS media.media_incident (
    id bigserial PRIMARY KEY,
    video_room_id uuid REFERENCES media.video_room(id) ON DELETE CASCADE,
    live_session_id uuid REFERENCES media.live_session(id) ON DELETE CASCADE,
    incident_type varchar(40) NOT NULL,
    detected_at timestamptz NOT NULL DEFAULT now(),
    last_valid_media_at timestamptz,
    recovered_at timestamptz,
    provider_attributable boolean,
    metadata jsonb NOT NULL DEFAULT '{}'::jsonb,
    CONSTRAINT media_incident_one_room_ck CHECK ((video_room_id IS NOT NULL) <> (live_session_id IS NOT NULL)),
    CONSTRAINT media_incident_recovery_ck CHECK (recovered_at IS NULL OR recovered_at >= detected_at)
);

CREATE INDEX IF NOT EXISTS media_incident_video_idx ON media.media_incident(video_room_id, detected_at DESC);
CREATE INDEX IF NOT EXISTS media_incident_live_idx ON media.media_incident(live_session_id, detected_at DESC);

COMMENT ON TABLE media.media_incident IS
'Operational evidence for disconnect/reconnect and provider attribution. No audio/video payloads, frames, transcripts, or TURN credentials.';

COMMIT;
