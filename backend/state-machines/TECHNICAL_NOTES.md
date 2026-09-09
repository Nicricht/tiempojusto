# Integration notes

## What is source-derived vs technical

The business timings, guards and outcomes in the Java classes come from V1.7. The implementation shape is a technical decision: immutable Java 21 records, explicit transition methods, typed error codes and caller-supplied `Instant now`.

No standalone extension-offer timeout is invented. V1.7 defines expiration by rejection, exhausted rounds/session ending, but does not freeze a separate offer timeout. The executable module therefore exposes expiration when the session ends and leaves any future UX countdown to an ADR/product update.

No transition out of `Payout.HELD_FOR_REVIEW` is invented. V1.7 says the operation stays unavailable until the incident is resolved, but does not freeze the exact resolution state machine. That belongs to the later Ledger/PaymentPort phase.

## Persistence mapping to PostgreSQL Schema V1.0

The existing schema can persist the machines with a few semantic mappings:

- Proposal `UPDATED` is an application transition/event; durable DB status remains `ACTIVE` after update.
- MetaNow `TRIGGERED` maps to DB `REACHED` unless the enum is later renamed.
- Auction `EXTENDED` maps to DB `OPEN` with a later `effective_end_at`; `CLOSED_NOW` maps to DB `CLOSED` with `close_reason='CLOSE_NOW'`.
- Winner `SELECTED/CONFIRMED/TIMED_OUT` maps to `auction_participant.confirmation_status` plus `confirmation_deadline`.
- Presential state uses `appointment.status` + `appointment_session.status`.
- Online terminal reasons (`FINISHED_FREE`, `FINISHED_RECONNECT_TIMEOUT`) should be persisted as an end-reason/event even if the DB status remains `ENDED`.
- Payout requires a durable representation of `HELD_FOR_REVIEW`; Schema V1.0 currently has no enum value with that exact meaning and should be aligned before production.
- Safety application states are richer than `case_decision_status`; persist the full timeline in SafetyCase/AuditEvent/OutboxEvent rather than collapsing evidence or outcomes.

## Transaction/locking policy for Spring application services

- Auction/Bid/Close Now: pessimistic row lock on Auction during final funding-confirmed commit, because winner/order/deadline are money-critical and simultaneous Bids are explicitly ordered by first valid backend commit.
- Winner confirmation/backups: lock the Auction/Appointment adjudication row when confirming or timing out to prevent two confirmed winners.
- Proposal: rely on the partial unique active constraint plus transaction; use optimistic versioning for edit races.
- MetaNow trigger: lock or compare-and-set the MetaNow row so 100% creates exactly one Auction.
- Session/Online: optimistic version + authoritative server timestamps; timer jobs re-read state before transitioning.
- Payout: row lock when releasing hold or adding review hold.
- Safety: optimistic version; S5 command must verify HumanReviewQueue approval in the same transaction.

## Timers

Timer jobs should carry only aggregate ID + expected state/version + deadline. On execution they must re-read the aggregate and perform the same transition guard as an interactive command. A stale timer becomes a no-op, never a forced state rewrite.

Required timers from V1.7 include Proposal 7d expiry, MetaNow expiry, Auction deadline/anti-sniping deadline, Winner 3m, Handshake 5m (+optional 5m), Presential free 5m, Online join 3m, Online free 2m, paid consent 30s, Online reconnect 2m, Live reconnect 2m, payout hold 60m, appeal 7d window.

## Outbox/event rule

Every successful money-, timer-, winner-, session- or safety-relevant transition should write its aggregate change and `OutboxEvent` in the same PostgreSQL transaction. WebSocket and external adapters consume outbox events; they never become the source of truth.
