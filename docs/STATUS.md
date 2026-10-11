# Project status

_Last updated: 2026-10-11_

## What is verified, and how

| Layer | State | How it was checked |
| --- | --- | --- |
| `core/crypto` + `core/protocol` | **387 checks pass** | `tools/selftest/run.sh`, and `./gradlew testDebugUnitTest` in CI (`SelfTests.kt`) |
| Relay API (`server/node`, `server/cloudflare`) | **49 checks pass against both** | `tools/selftest/run.sh --relay` with a relay running locally |
| Cloudflare worker packaging | **deploys** | `wrangler deploy --dry-run` (bundle 15.7 KiB, `MAILBOX` binding present) |
| SQL in `data/` + `engine/` | **schema-consistent** | `python3 tools/static-checks/check_sql_schema.py` (10 tables, 59 columns) |
| Kotlin symbols across modules | **consistent** | `python3 tools/static-checks/check_symbols.py` (imports, receivers, constructors) |
| Android app compile | **not yet run here** | needs the Android SDK + Maven; only GitHub Actions can do it — see below |

The Android modules (`ui`, `service`, `di`, `engine`, `data`) were written without a compiler in
the environment that produced them, so the two static checkers above exist as a safety net. They
catch renamed members, missing imports and schema drift, but **the first `assembleDebug` in CI is
the real gate.**

## First thing to do next

1. Copy `docs/ci/android-workflow.yml.template` to `.github/workflows/android.yml` (GitHub web UI
   → Actions → New workflow; the token available to the agent cannot write workflow files).
2. Push, then read the first build. Expect the class of problem a static checker cannot see:
   Compose compiler complaints, an Android API that needs a newer `compileSdk`, or a JUnit run
   that is green but empty.
3. Fix, repeat, then download `Ping-2.0.0.apk` (artifact or the rolling `latest` release).

## Known gaps / deliberate decisions

* **Release builds are not minified.** R8 with a hand-written ruleset cannot be smoke-tested
  without a device, and a stripped class that only fails at runtime is worse than a larger APK.
  `proguard-rules.pro` is maintained for the day that flips.
* **Release builds are signed with the debug key** unless `keystore.properties` (or the
  `KEYSTORE_*` environment variables) is present. Fine for sideloading, not for a store.
* **`androidTest` is empty.** The crypto suite is pure JVM and runs as a unit test on purpose; an
  instrumented test would need a device or an emulator in CI.
* **No push notifications (FCM).** Keeping the long poll means no third party learns when a
  message arrives. The cost is the persistent "connected" notification and a little battery.
* **Groups are not implemented.** The protocol is pairwise; a group would be a fan-out of the
  same ratchets.
* **Cloudflare free-tier limits** (100k requests/day) are ample but not unlimited; the README of
  that relay says what to do when they are hit.

## Useful commands

```bash
# crypto + protocol (no Android SDK needed)
tools/selftest/run.sh

# plus the live relay suite
node server/node/server.js --port 8080 &
PING_RELAY=http://127.0.0.1:8080 tools/selftest/run.sh --relay

# static checks (run after editing data/ or engine/)
python3 tools/static-checks/check_sql_schema.py
python3 tools/static-checks/check_symbols.py --strict

# the real build
./gradlew assembleDebug testDebugUnitTest
```
