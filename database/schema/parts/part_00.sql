-- ============================================================================
-- TIEMPOJUSTO - PostgreSQL/PostGIS Physical Schema V1.0
-- P0.2: DDL + constraints + indexes + minimal integrity triggers
--
-- Functional source of truth: TiempoJusto Documento Maestro V1.7 (2026-08-14)
-- Physical source: TiempoJusto_Modelo_ER_Fisico_V1_0.docx
-- Target: PostgreSQL 16+ with PostGIS
--
-- IMPORTANT
-- 1) V1.7 business rules remain the functional authority.
-- 2) This file materializes the approved/proposed physical ER. It does not
--    choose KYC, PaymentPort, routing/ETA or WebRTC providers.
-- 3) State-machine transitions, OpenAPI contracts and WebSocket contracts are
--    separate following milestones. Only minimal DB-side integrity is encoded.
-- 4) Where V1.7/ER does not freeze a closed catalog, VARCHAR is deliberately
--    used instead of inventing an enum.
-- 5) Card PAN/CVV, raw KYC documents and raw biometrics MUST NOT be stored here.
-- ============================================================================

BEGIN;

-- --------------------------------------------------------------------------
-- 0. Extensions and schemas
-- --------------------------------------------------------------------------
CREATE EXTENSION IF NOT EXISTS pgcrypto;
CREATE EXTENSION IF NOT EXISTS citext;
CREATE EXTENSION IF NOT EXISTS postgis;

CREATE SCHEMA IF NOT EXISTS platform;
CREATE SCHEMA IF NOT EXISTS iam;
CREATE SCHEMA IF NOT EXISTS profile;
CREATE SCHEMA IF NOT EXISTS geo;
CREATE SCHEMA IF NOT EXISTS market;
CREATE SCHEMA IF NOT EXISTS auction;
CREATE SCHEMA IF NOT EXISTS appointment;
CREATE SCHEMA IF NOT EXISTS media;
CREATE SCHEMA IF NOT EXISTS finance;
CREATE SCHEMA IF NOT EXISTS safety;
CREATE SCHEMA IF NOT EXISTS comms;
CREATE SCHEMA IF NOT EXISTS reputation;

-- --------------------------------------------------------------------------
-- 1. Closed catalogs / enums supported by V1.7 + ER V1.0
-- --------------------------------------------------------------------------
CREATE TYPE platform.user_role AS ENUM ('HOST','BIDDER','ADMIN');
CREATE TYPE platform.account_status AS ENUM ('PENDING_VERIFICATION','ACTIVE','RESTRICTED','SUSPENDED','CLOSED');
CREATE TYPE platform.verification_status AS ENUM ('PENDING','VERIFIED','REJECTED','EXPIRED','REVIEW');
CREATE TYPE platform.profile_status AS ENUM ('DRAFT','ACTIVE','HIDDEN','SUSPENDED');
CREATE TYPE platform.media_type AS ENUM ('PHOTO','VIDEO');
CREATE TYPE platform.modality AS ENUM ('IN_PERSON','ONLINE');
CREATE TYPE platform.place_type AS ENUM ('PUBLIC','HOST_PRIVATE');
CREATE TYPE platform.proposal_status AS ENUM ('ACTIVE','WITHDRAWN','EXPIRED','INVALID');
CREATE TYPE platform.availability_status AS ENUM ('ACTIVE','CANCELLED','CONVERTED','EXPIRED');
CREATE TYPE platform.meta_status AS ENUM ('ACTIVE','REACHED','EXPIRED','CANCELLED');
CREATE TYPE platform.auction_status AS ENUM ('OPEN','CLOSED','CANCELLED','FAILED');
CREATE TYPE platform.eligibility_status AS ENUM ('ELIGIBLE','BLOCKED','REJECTED','DISQUALIFIED');
CREATE TYPE platform.bid_type AS ENUM ('NORMAL','INSTANT_CLOSE');
CREATE TYPE platform.bid_status AS ENUM ('VALID','REJECTED','OUTBID','WINNING','CANCELLED');
CREATE TYPE platform.funds_status AS ENUM ('PENDING','RESERVED','RELEASED','CAPTURED','FAILED','EXPIRED');
CREATE TYPE platform.appointment_status AS ENUM ('AWAITING_CONFIRMATION','CONFIRMED','EN_ROUTE','ARRIVED','HANDSHAKE','FREE','PAID_ACTIVE','ENDED','NO_SHOW','CANCELLED');
CREATE TYPE platform.arrival_status AS ENUM ('PENDING','VALID','INVALID');
CREATE TYPE platform.handshake_status AS ENUM ('PENDING','COMPLETED','EXPIRED','INVALID');
CREATE TYPE platform.session_status AS ENUM ('WAITING','FREE_ACTIVE','PAID_PENDING_ACCEPTANCE','PAID_ACTIVE','PAUSED','RECONNECTING','ENDED');
CREATE TYPE platform.segment_type AS ENUM ('FREE','PAID','PAUSE','RECONNECT');
CREATE TYPE platform.extension_status AS ENUM ('OPEN','ACCEPTED','REJECTED','EXPIRED','CANCELLED');
CREATE TYPE platform.offer_status AS ENUM ('PENDING','ACCEPTED','REJECTED','SUPERSEDED','EXPIRED');
CREATE TYPE platform.live_status AS ENUM ('STARTING','LIVE','ENDED','FAILED');
CREATE TYPE platform.live_access_type AS ENUM ('TICKET','BIDDER_INCLUDED');
CREATE TYPE platform.ticket_status AS ENUM ('RESERVED','PAID','REFUNDED','FAILED');
CREATE TYPE platform.video_room_status AS ENUM ('WAITING','FREE_ONLINE','ACCEPTANCE_PENDING','PAID_ACTIVE','RECONNECTING','ENDED');
CREATE TYPE platform.ledger_owner_type AS ENUM ('USER','PLATFORM','PROVIDER_CLEARING');
CREATE TYPE platform.ledger_account_type AS ENUM ('AVAILABLE','PENDING','ESCROW','REVENUE','REFUND','DISPUTE');
CREATE TYPE platform.ledger_tx_type AS ENUM ('CAPTURE','SESSION_SETTLEMENT','TICKET_SETTLEMENT','REFUND','PAYOUT','PENALTY','COMPENSATION','CHARGEBACK');
CREATE TYPE platform.ledger_tx_status AS ENUM ('PENDING','POSTED','REVERSED');
CREATE TYPE platform.entry_direction AS ENUM ('DEBIT','CREDIT');
CREATE TYPE platform.refund_status AS ENUM ('PENDING','SUCCEEDED','FAILED');
CREATE TYPE platform.payout_status AS ENUM ('PENDING_HOLD','AVAILABLE','SENT','FAILED','REVERSED');
CREATE TYPE platform.dispute_status AS ENUM ('OPEN','WON','LOST','CLOSED');
CREATE TYPE platform.report_status AS ENUM ('OPEN','TRIAGED','CLOSED');
CREATE TYPE platform.evidence_type AS ENUM ('MEDIA_REF','EVENT_REF','PAYMENT_REF','GEO_REF','TEXT');
CREATE TYPE platform.safety_level AS ENUM ('S0','S1','S2','S3','S4','S5');
CREATE TYPE platform.case_decision_status AS ENUM ('OPEN','AUTO_ACTIONED','HUMAN_REVIEW','CLOSED');
CREATE TYPE platform.appeal_status AS ENUM ('OPEN','UPHELD','REDUCED','REVOKED','REJECTED_LATE');
CREATE TYPE platform.review_task_status AS ENUM ('QUEUED','IN_PROGRESS','RESOLVED');
CREATE TYPE platform.request_status AS ENUM ('PENDING','ACCEPTED','REJECTED','EXPIRED');

-- Technical-only closed values required by the physical ER. These are not new
-- product features; they are persistence labels and can be migrated if the
-- later executable state-machine milestone renames them.
CREATE TYPE platform.confirmation_status AS ENUM ('PENDING','CONFIRMED','DECLINED','EXPIRED');

-- --------------------------------------------------------------------------
-- 2. Shared helper functions
-- --------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION platform.set_updated_at()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    NEW.updated_at := clock_timestamp();
    RETURN NEW;
END;
$$;

CREATE OR REPLACE FUNCTION platform.valid_base_duration(p_modality platform.modality, p_minutes smallint)
RETURNS boolean
LANGUAGE sql
IMMUTABLE
AS $$
    SELECT CASE
        WHEN p_modality = 'IN_PERSON'::platform.modality THEN p_minutes IN (30,60,90)
        WHEN p_modality = 'ONLINE'::platform.modality THEN p_minutes IN (15,30,60)
        ELSE FALSE
    END;
$$;

CREATE OR REPLACE FUNCTION platform.ceil_to_5000(p_amount bigint)
RETURNS bigint
LANGUAGE sql
IMMUTABLE
STRICT
AS $$
    SELECT ((p_amount + 4999) / 5000) * 5000;
$$;

CREATE OR REPLACE FUNCTION platform.next_minimum_bid(p_current bigint)
RETURNS bigint
LANGUAGE sql
IMMUTABLE
STRICT
AS $$
    -- V1.7 FR-037: approx 10% of pot, rounded upward to CLP 5,000,
    -- with an absolute minimum increment of CLP 5,000.
    SELECT p_current + GREATEST(5000, platform.ceil_to_5000(CEIL(p_current * 0.10)::bigint));
$$;

-- --------------------------------------------------------------------------
-- 3. IAM
-- --------------------------------------------------------------------------
CREATE TABLE iam.app_user (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    public_id varchar(24) NOT NULL UNIQUE,
    role platform.user_role NOT NULL,
    account_status platform.account_status NOT NULL DEFAULT 'PENDING_VERIFICATION',
    email citext UNIQUE,
    phone_e164 varchar(32) UNIQUE,
    email_verified_at timestamptz,
    phone_verified_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    lock_version integer NOT NULL DEFAULT 0 CHECK (lock_version >= 0),
    CONSTRAINT app_user_contact_ck CHECK (email IS NOT NULL OR phone_e164 IS NOT NULL)
);

CREATE TRIGGER app_user_set_updated_at
BEFORE UPDATE ON iam.app_user
FOR EACH ROW EXECUTE FUNCTION platform.set_updated_at();

CREATE TABLE iam.public_identity (
    user_id uuid PRIMARY KEY REFERENCES iam.app_user(id) ON DELETE CASCADE,
    display_name varchar(80) NOT NULL,
    username citext NOT NULL UNIQUE,
    public_age smallint CHECK (public_age >= 18),
    bio text,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE TRIGGER public_identity_set_updated_at
BEFORE UPDATE ON iam.public_identity
FOR EACH ROW EXECUTE FUNCTION platform.set_updated_at();
