#!/usr/bin/env python3
import json, pathlib, sys

ROOT = pathlib.Path(__file__).resolve().parent
P = json.loads((ROOT / "protocol.json").read_text(encoding="utf-8"))
EXPECTED = {"AUCTION_STARTED","BID_ACCEPTED","BID_REJECTED_PRIVATE","POZO_UPDATED","TIMER_RESET_2M","AUCTION_CLOSED","CLOSE_NOW_EXECUTED","WINNER_SELECTED","BACKUP_OFFERED","WINNER_CONFIRMED","ARRIVAL_CONFIRMED","HANDSHAKE_CONFIRMED","FREE_PERIOD_STARTED","PAID_ACTIVE_STARTED","SESSION_PAUSED","SESSION_RESUMED","SESSION_FINISHED","EXTENSION_PROPOSED","EXTENSION_COUNTERED","EXTENSION_CONFIRMED","LIVE_STARTED","LIVE_CONNECTION_LOST","LIVE_ENDED","ONLINE_CONNECTION_LOST","BILLING_PAUSED_CONNECTION","PAYOUT_AVAILABLE","REPORT_STATUS_CHANGED"}
errors=[]
if P.get("protocolVersion") != "1.0": errors.append("protocolVersion must be 1.0")
if P.get("transport",{}).get("endpoint") != "/ws/v1": errors.append("endpoint must be /ws/v1")
actual=set(P.get("events",{}))
if actual != EXPECTED:
    errors.append(f"event catalog mismatch missing={sorted(EXPECTED-actual)} extra={sorted(actual-EXPECTED)}")
required_topics={"user:{userId}","auction:{auctionId}","session:{sessionId}","live:{liveId}"}
if set(P.get("topics",{})) != required_topics: errors.append("topic catalog mismatch")
for topic,meta in P.get("topics",{}).items():
    for ev in meta.get("events",[]):
        if ev not in actual: errors.append(f"{topic} routes unknown event {ev}")
for ev,meta in P.get("events",{}).items():
    if not meta.get("payloadRequired"): errors.append(f"{ev} has no payloadRequired")
    if meta.get("source") != "V1.7": errors.append(f"{ev} source must be V1.7")
if not P.get("authority",{}).get("serverIsSourceOfTruth"): errors.append("server must be source of truth")
if P.get("authority",{}).get("businessMutationsOverWebSocket"): errors.append("business mutations must not use WebSocket")
if P.get("authentication",{}).get("queryStringTokensAllowed") is not False: errors.append("query string tokens must be forbidden")
if P.get("replay",{}).get("delivery") != "at-least-once": errors.append("delivery must be at-least-once")
if errors:
    print("FAIL")
    for e in errors: print("-",e)
    sys.exit(1)
print(f"PASS: {len(actual)} V1.7 events, {len(required_topics)} topic families, protocol {P['protocolVersion']}")
