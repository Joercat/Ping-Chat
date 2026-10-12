#!/usr/bin/env bash
# Runs the Ping self-tests with nothing but a JDK 17+ and curl.
#
#   tools/selftest/run.sh                  # crypto + protocol suite (no relay needed)
#   tools/selftest/run.sh --relay          # adds the live relay suite (needs a relay, see below)
#   KOTLINC=/opt/kotlinc tools/selftest/run.sh
#
# The Kotlin compiler is downloaded once into .selftest/ (git-ignored) from the official GitHub
# release, so this works on a machine with no Android SDK, no Gradle and no Maven access. If a
# Kotlin distribution is already installed, point KOTLINC at it and nothing is downloaded.
#
# Live relay suite:
#   node server/node/server.js --port 8080 &        # or: npx wrangler dev --port 8787
#   PING_RELAY=http://127.0.0.1:8080 tools/selftest/run.sh --relay
set -euo pipefail

KOTLIN_VERSION="${KOTLIN_VERSION:-2.0.21}"
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
WORK="$ROOT/.selftest"
OUT="$WORK/out"

command -v java >/dev/null || { echo "java is required (JDK 17 or newer)"; exit 1; }

# ---- locate a Kotlin compiler ----------------------------------------------------------
KOTLINC="${KOTLINC:-}"
if [ -z "$KOTLINC" ]; then
  if [ -x "$WORK/kotlinc/bin/kotlinc" ]; then
    KOTLINC="$WORK/kotlinc"
  else
    echo "downloading Kotlin $KOTLIN_VERSION compiler…"
    mkdir -p "$WORK"
    curl -fsSL "https://github.com/JetBrains/kotlin/releases/download/v$KOTLIN_VERSION/kotlin-compiler-$KOTLIN_VERSION.zip" \
      -o "$WORK/kotlinc.zip"
    (cd "$WORK" && unzip -q -o kotlinc.zip)
    KOTLINC="$WORK/kotlinc"
  fi
fi
[ -x "$KOTLINC/bin/kotlinc" ] || { echo "no kotlinc at $KOTLINC/bin/kotlinc (set KOTLINC=…)"; exit 1; }
echo "using Kotlin compiler: $KOTLINC"

SRC="$ROOT/app/src/main/java/com/ping/app"
TESTS="$ROOT/app/src/test/java/com/ping/app/core/checks"
mkdir -p "$OUT"

echo "compiling core, suites and runners…"
"$KOTLINC/bin/kotlinc" \
  "$SRC"/core/crypto/*.kt "$SRC"/core/protocol/*.kt \
  "$TESTS"/CryptoCoreTest.kt "$TESTS"/ProtocolTest.kt "$TESTS"/RelayE2E.kt \
  "$ROOT"/tools/selftest/JUnitStub.kt "$ROOT"/tools/selftest/Main.kt "$ROOT"/tools/selftest/RelayE2ERunner.kt \
  -jvm-target 17 -d "$OUT" 2>&1 | grep -vE "^(warning:|WARNING)" || true

STDLIB="$KOTLINC/lib/kotlin-stdlib.jar"
CP="$OUT:$STDLIB"

echo
echo "== crypto + protocol =="
java -cp "$CP" MainKt

if [ "${1:-}" = "--relay" ]; then
  echo
  echo "== live relay =="
  java -cp "$CP" RelayE2ERunnerKt
fi
