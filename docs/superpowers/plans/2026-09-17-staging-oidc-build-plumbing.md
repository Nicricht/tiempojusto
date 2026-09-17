# Staging OIDC Build Plumbing Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ensure the production frontend container actually receives the public OIDC Authorization Code + PKCE configuration required by staging.

**Architecture:** Keep OIDC provider-neutral. Pass only public browser configuration as Docker build args from `ops/staging/docker-compose.staging.yml` into the Vite build in `frontend/Dockerfile`; keep all provider secrets server-side. CI renders the Compose model and asserts those public values are present.

**Tech Stack:** React/Vite, Docker multi-stage build, Docker Compose, GitHub Actions.

**Spec:** `docs/product/MVP_PRODUCTION_V1.md`, issue #25 acceptance, and `docs/runtime/OAUTH2_JWT_AUTH_V1.md`.

## Global Constraints

- Frontend must not contain client secrets.
- Authorization Code + PKCE S256 remains the browser auth flow.
- Backend/JWKS remains authoritative for JWT validation.
- Do not change TiempoJusto business rules or `PENDING_ROUNDING_POLICY`.

---

### Task 1: Add a failing staging contract check

**Files:**
- Modify: `.github/workflows/staging-ops-hardening.yml`

**Interfaces:**
- Consumes: `docker compose ... config`.
- Produces: CI assertions for public OIDC build args.

- [ ] **Step 1: Write the failing test**

Add staging test env values for:

```text
VITE_TJ_OIDC_AUTHORIZATION_ENDPOINT=https://staging.example.test/oidc/auth
VITE_TJ_OIDC_TOKEN_ENDPOINT=https://staging.example.test/oidc/token
VITE_TJ_OIDC_CLIENT_ID=tiempojusto-web
VITE_TJ_OIDC_SCOPE=openid profile
VITE_TJ_OIDC_REDIRECT_URI=https://staging.example.test/
```

Then assert `/tmp/staging-compose.yml` contains the authorization endpoint, token endpoint and client id under the frontend build args.

- [ ] **Step 2: Run test to verify it fails**

Run the PR `Staging Ops Hardening` workflow. Expected: `Validate staging Compose` or the new OIDC assertion step fails because the Compose frontend build currently omits the OIDC arguments.

- [ ] **Step 3: Commit RED state**

Commit message:

```text
test: require OIDC config in staging frontend build
```

---

### Task 2: Wire OIDC public configuration through Docker and Compose

**Files:**
- Modify: `frontend/Dockerfile`
- Modify: `ops/staging/docker-compose.staging.yml`
- Modify: `ops/staging/.env.example`
- Modify: `docs/ops/STAGING_RUNBOOK_V1.md`

**Interfaces:**
- Consumes: `VITE_TJ_OIDC_AUTHORIZATION_ENDPOINT`, `VITE_TJ_OIDC_TOKEN_ENDPOINT`, `VITE_TJ_OIDC_CLIENT_ID`, `VITE_TJ_OIDC_SCOPE`, `VITE_TJ_OIDC_REDIRECT_URI`.
- Produces: a Vite production bundle configured for the selected provider-neutral OIDC issuer.

- [ ] **Step 1: Add Docker build args and ENV values**

`frontend/Dockerfile` must forward the five public OIDC variables into `npm run build`.

- [ ] **Step 2: Add Compose build args**

`ops/staging/docker-compose.staging.yml` must require authorization endpoint, token endpoint and client id, while keeping scope default `openid profile`; redirect URI may default to `https://${TJ_STAGING_HOST}/`.

- [ ] **Step 3: Document the public configuration**

Add names to `.env.example` and explain in the staging runbook that these values are public SPA configuration, not secrets.

- [ ] **Step 4: Run verification**

Expected: `Staging Ops Hardening`, frontend build and security scan pass.

- [ ] **Step 5: Commit GREEN state**

Commit message:

```text
feat: wire OIDC config into staging frontend build
```

---

### Task 3: Final verification and merge

**Files:**
- Review all changed files in the PR.

**Interfaces:**
- Produces: merged `main` with no claim that an external OIDC provider has been proven.

- [ ] **Step 1: Confirm CI is fully green**

Require zero failed, queued or in-progress PR workflows for the head SHA.

- [ ] **Step 2: Review patch for secrets**

Verify only placeholder/example public values were added and no token/client secret was introduced.

- [ ] **Step 3: Merge**

Squash merge only after green CI.

- [ ] **Step 4: Verify main push workflows**

Record the post-merge status and keep #25/#27 open until real staging evidence exists.
