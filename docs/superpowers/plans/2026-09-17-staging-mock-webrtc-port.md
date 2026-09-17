# Staging Mock WebRTC Port Fix

## Goal
Make the existing `staging` profile able to start with the configured default `TJ_MEDIA_PROVIDER=MOCK`, without enabling real TURN yet.

## Small-step plan
1. Add one regression assertion proving `staging` + `MOCK` must provide `WebRtcPort`.
2. Verify the test fails before production change.
3. Add the smallest conditional staging wiring for `MockWebRtcPort`.
4. Verify Runtime Integration and staging-related CI.
5. Merge and verify Render `/healthz`.
