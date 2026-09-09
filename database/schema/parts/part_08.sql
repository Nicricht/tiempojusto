CREATE OR REPLACE FUNCTION geo.purge_expired_operational_locations(p_limit integer DEFAULT 10000)
RETURNS integer
LANGUAGE plpgsql
AS $$
DECLARE v_deleted integer;
BEGIN
    WITH doomed AS (
        SELECT id
          FROM geo.operational_location_event
         WHERE preservation_hold = false
           AND purge_after <= clock_timestamp()
         ORDER BY purge_after
         LIMIT p_limit
         FOR UPDATE SKIP LOCKED
    )
    DELETE FROM geo.operational_location_event g
     USING doomed d
     WHERE g.id = d.id;
    GET DIAGNOSTICS v_deleted = ROW_COUNT;
    RETURN v_deleted;
END;
$$;

CREATE OR REPLACE FUNCTION platform.purge_expired_idempotency(p_limit integer DEFAULT 10000)
RETURNS integer
LANGUAGE plpgsql
AS $$
DECLARE v_deleted integer;
BEGIN
    WITH doomed AS (
        SELECT id
          FROM platform.idempotency_record
         WHERE expires_at <= clock_timestamp()
         ORDER BY expires_at
         LIMIT p_limit
         FOR UPDATE SKIP LOCKED
    )
    DELETE FROM platform.idempotency_record r
     USING doomed d
     WHERE r.id = d.id;
    GET DIAGNOSTICS v_deleted = ROW_COUNT;
    RETURN v_deleted;
END;
$$;

-- --------------------------------------------------------------------------
-- 17. Comments on rules intentionally left for P0.3/P0.4/provider adapters
-- --------------------------------------------------------------------------
COMMENT ON SCHEMA auction IS
'Backend/server remains source of truth. Full auction state machine, risk decisions, reservation release and winner/supplement chain are finalized in P0.3/P0.4; DB already serializes accepted Bid insertion by locking Auction root.';

COMMENT ON SCHEMA appointment IS
'Arrival never authorizes billing by itself. FREE/PAID transitions require the defined Handshake/Online bilateral flows and are completed in executable state machines.';

COMMENT ON SCHEMA finance IS
'V1.7 split rules: session 80% HOST / 20% TiempoJusto on amount actually generated; Live Ticket 70%/30% on distributable revenue. Exact settlement postings belong to PaymentPort + ledger service; DB enforces arithmetic/balance/hold but does not invent provider fees.';

COMMENT ON SCHEMA media IS
'Online V1.7: join 3m, FREE_ONLINE 2m, camera required, bilateral paid confirmation 30s, media tolerance 5s, reconnect 2m, no private recording by default. Media-tolerance semantics depend on WebRTC heartbeats and are finalized in P0.4.';

COMMENT ON SCHEMA safety IS
'V1.7 Safety: S0 no infringement; S1 warning; S2 24h; S3 7d; S4 30d; S5 indefinite/closure. Levels may be skipped for severity. S5/irreversible requires HumanReviewQueue; appeal window 7 days.';

-- --------------------------------------------------------------------------
-- 18. Sanity checks for schema deployment
-- --------------------------------------------------------------------------
DO $$
BEGIN
    IF platform.next_minimum_bid(100000) <> 110000 THEN
        RAISE EXCEPTION 'next_minimum_bid sanity check failed';
    END IF;
    IF platform.next_minimum_bid(10000) <> 15000 THEN
        RAISE EXCEPTION 'minimum increment sanity check failed';
    END IF;
    IF NOT platform.valid_base_duration('IN_PERSON'::platform.modality, 60::smallint) OR platform.valid_base_duration('ONLINE'::platform.modality, 90::smallint) THEN
        RAISE EXCEPTION 'duration sanity check failed';
    END IF;
END;
$$;

COMMIT;

-- ============================================================================
-- END V1.0
-- Known next milestones:
-- P0.3 locking/idempotency/outbox transaction contracts (service-level details)
-- P0.4 executable state machines and guards
-- P0.5 OpenAPI definitive contracts
-- P0.6 WebSocket topics/resync/versioning
-- ============================================================================
