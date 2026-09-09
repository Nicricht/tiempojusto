# TiempoJusto State Machines V1.0

Executable domain-state-machine baseline for TiempoJusto, derived from Documento Maestro V1.7 (14 Aug 2026).

## Purpose

This module converts the V1.7 conceptual transition tables into executable Java 21 domain logic. It intentionally stays framework-independent so it can live inside the approved modular-monolith / hexagonal backend and be called from Spring Boot application services.

## Included aggregates

- Proposal
- MetaNow
- Auction + anti-sniping / Close Now
- Winner confirmation + backup chain
- Presential Appointment + Session
- Online session
- Extension negotiation
- Live
- Payout hold
- SafetyCase + Appeal

## Important scope boundary

This module does **not** select external providers, create OpenAPI/WebSocket contracts, or invent rules that V1.7 leaves pending. Where V1.7 does not define a transition, the code intentionally exposes no transition.

## Run contract tests

Linux/macOS:

```bash
./run-tests.sh
```

Windows PowerShell:

```powershell
./run-tests.ps1
```

The test runner uses only the JDK, so no Maven/Gradle download is required.

## Integration target

Backend: Java 21 + Spring Boot. PostgreSQL/PostGIS remains the source of transactional truth; callers should execute aggregate transitions inside transactions and use locking/idempotency according to the accompanying specification.
