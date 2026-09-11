#!/usr/bin/env bash
set -euo pipefail
rm -rf out
mkdir -p out
find src/main/java src/test/java -name '*.java' -print0 | xargs -0 javac --release 21 -d out
java -cp out cl.tiempojusto.finance.FinanceContractTests
