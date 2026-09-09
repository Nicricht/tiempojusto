#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")" && pwd)"
OUT="$ROOT/build/classes"
rm -rf "$ROOT/build"
mkdir -p "$OUT"
find "$ROOT/src/main/java" "$ROOT/src/test/java" -name '*.java' -print0 | xargs -0 javac --release 21 -d "$OUT"
java -ea -cp "$OUT" cl.tiempojusto.statemachine.StateMachineContractTests
