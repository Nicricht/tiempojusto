-- TiempoJusto V1.11 - ephemeral WebRTC signaling
-- No audiovisual payloads or TURN credentials are stored.

BEGIN;

CREATE UNLOGGED TABLE IF NOT EXISTS media.webrtc_signal (
    id bigserial PRIMARY KEY,
    session_id uuid NOT NULL,
    video_room_id uuid NOT NULL,
    sender_user_id uuid NOT NULL,
    recipient_user_id uuid NOT NULL,
    signal_type varchar(24) NOT NULL CHECK (signal_type IN ('OFFER','ANSWER','ICE_CANDIDATE','ICE_COMPLETE')),
    sdp text,
    candidate text,
    sdp_mid varchar(128),
    sdp_mline_index integer,
    created_at timestamptz NOT NULL DEFAULT now(),
    expires_at timestamptz NOT NULL,
    CONSTRAINT webrtc_signal_payload_ck CHECK (
        (signal_type IN ('OFFER','ANSWER') AND sdp IS NOT NULL AND candidate IS NULL)
        OR (signal_type = 'ICE_CANDIDATE' AND candidate IS NOT NULL AND sdp IS NULL)
        OR (signal_type = 'ICE_COMPLETE' AND sdp IS NULL AND candidate IS NULL)
    ),
    CONSTRAINT webrtc_signal_short_ttl_ck CHECK (
        expires_at > created_at AND expires_at <= created_at + interval '2 minutes'
    )
);

CREATE INDEX IF NOT EXISTS webrtc_signal_recipient_idx
    ON media.webrtc_signal(session_id, recipient_user_id, id);
CREATE INDEX IF NOT EXISTS webrtc_signal_expiry_idx
    ON media.webrtc_signal(expires_at);

COMMENT ON TABLE media.webrtc_signal IS
'Ephemeral signaling mailbox. SDP and ICE expire within 2 minutes. No audiovisual content or TURN credentials.';

COMMIT;
