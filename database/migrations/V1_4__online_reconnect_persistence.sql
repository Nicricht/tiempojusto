-- TiempoJusto V1.4 - Online reconnect persistence hardening
-- Source: Documento Maestro V1.7 FR080-FR082 / Annex L.
-- No provider is selected here; this migration only hardens persisted lifecycle invariants.

BEGIN;

ALTER TABLE media.video_room
    ADD CONSTRAINT video_reconnect_pair_ck
    CHECK ((media_lost_at IS NULL) = (reconnect_deadline IS NULL));

CREATE UNIQUE INDEX one_open_paid_segment_uidx
ON appointment.session_segment(session_id)
WHERE segment_type = 'PAID' AND ended_at IS NULL;

CREATE UNIQUE INDEX one_open_reconnect_segment_uidx
ON appointment.session_segment(session_id)
WHERE segment_type = 'RECONNECT' AND ended_at IS NULL;

CREATE UNIQUE INDEX one_unrecovered_online_incident_uidx
ON media.media_incident(video_room_id)
WHERE video_room_id IS NOT NULL
  AND incident_type = 'ONLINE_MEDIA_INTERRUPTION'
  AND recovered_at IS NULL;

COMMENT ON INDEX appointment.one_open_paid_segment_uidx IS
'At most one billable PAID segment may remain open for a session.';

COMMENT ON INDEX appointment.one_open_reconnect_segment_uidx IS
'At most one non-billable RECONNECT segment may remain open for a session.';

COMMENT ON INDEX media.one_unrecovered_online_incident_uidx IS
'At most one unresolved Online media interruption may exist per private video room.';

COMMIT;
