# OIDC External Staging Proof Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Turn the remaining OIDC staging gate into an executable, repeatable public proof that validates discovery metadata and JWKS without using secrets.

**Architecture:** Add a small Python verifier under `ops/staging` using only the standard library. Unit tests mock HTTP retrieval so CI is deterministic. `staging-smoke.yml` will require the public discovery URL and expected issuer, then call the verifier after the existing edge checks.

**Tech Stack:** Python 3 standard library, GitHub Actions, OIDC discovery/JWKS.

**Spec:** issue #27, issue #25, `docs/runtime/OAUTH2_JWT_AUTH_V1.md`, `docs/ops/STAGING_RUNBOOK_V1.md`.

## Global Constraints

- OIDC proof uses public metadata only; no client secret or access token.
- Discovery URL, declared issuer, and `jwks_uri` must use HTTPS.
- Issuer comparison is exact.
- JWKS must contain at least one key.
- This proof does not mark login flow complete by itself.
- Do not change product business rules or `PENDING_ROUNDING_POLICY`.

---

### Task 1: Write RED unit tests

**Files:**
- Create: `ops/staging/test_verify_oidc_public.py`

**Interfaces:**
- Consumes: future `validate_metadata(discovery_url, expected_issuer, fetch_json)` function.
- Produces: deterministic contract for HTTPS, issuer equality, HTTPS JWKS and non-empty key set.

- [ ] **Step 1: Write failing tests**

Cover happy path, non-HTTPS discovery URL, issuer mismatch, non-HTTPS `jwks_uri`, and empty `keys`.

- [ ] **Step 2: Run test to verify RED**

Run `python3 -m unittest ops/staging/test_verify_oidc_public.py`.
Expected: import failure because `verify_oidc_public.py` does not exist.

- [ ] **Step 3: Commit RED state**

Commit `test: define OIDC public proof contract`.

---

### Task 2: Implement the verifier

**Files:**
- Create: `ops/staging/verify_oidc_public.py`
- Modify: `.github/workflows/staging-ops-hardening.yml`

**Interfaces:**
- Produces: `validate_metadata(discovery_url: str, expected_issuer: str, fetch_json=fetch_json) -> dict` and CLI exit status 0 on valid public OIDC metadata.

- [ ] **Step 1: Implement minimal verifier**

Use `urllib.request` and `json`; reject non-HTTPS URLs; require exact issuer; fetch HTTPS JWKS and require non-empty `keys` list.

- [ ] **Step 2: Add unit test execution to Staging Ops Hardening**

Run `python3 -m unittest ops/staging/test_verify_oidc_public.py` before container build verification.

- [ ] **Step 3: Verify GREEN**

PR `Staging Ops Hardening` must pass the unit tests.

- [ ] **Step 4: Commit**

Commit `feat: add public OIDC staging verifier`.

---

### Task 3: Wire external smoke workflow

**Files:**
- Modify: `.github/workflows/staging-smoke.yml`
- Modify: `docs/ops/STAGING_RUNBOOK_V1.md`

**Interfaces:**
- Consumes: workflow inputs `base_url`, `oidc_discovery_url`, `oidc_expected_issuer`.
- Produces: repeatable public proof for edge + OIDC discovery/JWKS.

- [ ] **Step 1: Add required workflow inputs**

Both OIDC inputs are public values and required.

- [ ] **Step 2: Checkout repository and run verifier**

After existing edge/auth-boundary checks, run:

```bash
python3 ops/staging/verify_oidc_public.py "$OIDC_DISCOVERY_URL" "$OIDC_EXPECTED_ISSUER"
```

- [ ] **Step 3: Document evidence procedure**

Runbook must state that successful smoke proves public discovery/JWKS reachability and exact issuer only, while browser login still needs separate evidence.

- [ ] **Step 4: Verify PR workflows**

Require zero failed, queued or in-progress PR workflows.

- [ ] **Step 5: Merge and verify main**

Squash merge only after green CI. Keep #25/#27 open until external workflow is actually dispatched against deployed staging and browser login is proven.
