# Project status

_Last updated: 2026-10-11 (second revision: first CI run diagnosed)_

## What is verified, and how

| Layer | State | How it was checked |
| --- | --- | --- |
| `core/crypto` + `core/protocol` | **387 checks pass** | `tools/selftest/run.sh`, and `./gradlew testDebugUnitTest` in CI (`SelfTests.kt`) |
| Relay API (`server/node`, `server/cloudflare`) | **49 checks pass against both** | `tools/selftest/run.sh --relay` with a relay running locally |
| Cloudflare worker packaging | **deploys** | `wrangler deploy --dry-run` (bundle 15.7 KiB, `MAILBOX` binding present) |
| SQL in `data/` + `engine/` | **schema-consistent** | `python3 tools/static-checks/check_sql_schema.py` (10 tables, 59 columns) |
| Kotlin symbols across modules | **consistent** | `python3 tools/static-checks/check_symbols.py` (imports, receivers, constructors) |
| Gradle wrapper | **usable** | `gradle-wrapper.jar` is a valid archive (33 entries, `GradleWrapperMain` present), `gradlew` is the genuine 8.x script, `distributionUrl` = gradle-8.7-bin.zip |
| Android resources | **well-formed** | every XML parses, all vector drawables carry the four required attributes, `R.*` references resolve, adaptive icons cover API 26+ |
| Android app compile | **not yet run** | needs the Android SDK + Maven; only GitHub Actions can do it — first attempt failed in the SDK step (see below) |

The Android modules (`ui`, `service`, `di`, `engine`, `data`) were written without a compiler in
the environment that produced them, so the two static checkers above exist as a safety net. They
catch renamed members, missing imports and schema drift, but **the first `assembleDebug` in CI is
the real gate.**

## CI run log

### Run 1 (2026-10-11) — failed before any project step

```
Set up Android SDK -> failure
Error: The process '/usr/local/lib/android/sdk/cmdline-tools/16.0/bin/sdkmanager' failed with exit code 1
```

**Cause — upstream, nothing to do with this repository.** `android-actions/setup-android@v3`
runs `sdkmanager tools`, and Google retired the legacy `tools` SDK package, so the action hard
fails on every run (android-actions/setup-android#537). The runner image already ships the SDK;
`assembleDebug` needs a platform and build-tools, not the legacy package.

**Fix applied to the template:**

* `android-actions/setup-android@v3` → `@v4` (no longer requests `tools`), with an explicit
  `packages: platform-tools`
* a new step that accepts licences and installs exactly what the project compiles against
  (`platforms;android-34`, `build-tools;34.0.0`) and writes `local.properties`
* a failure-only upload of the test reports, so a red test run is readable without a re-run

The workflow file itself (`.github/workflows/android.yml`) has to be updated by hand — the token
available to the agent cannot write workflow files. Everything else in the run was skipped.

## First thing to do next

1. Replace `.github/workflows/android.yml` with the current `docs/ci/android-workflow.yml.template`
   (GitHub web UI: open the file → pencil → select all → paste → commit).
2. Re-run the workflow. The SDK step should pass; the next thing that can fail is the first real
   compile of the Android modules. Expect the class of problem a static checker cannot see:
   Compose compiler complaints, an Android API that needs a newer `compileSdk`, or a green-but-empty
   JUnit run.
3. Fix, repeat, then download `Ping-2.0.0.apk` (artifact, or the rolling `latest` release).

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
