# Project status

_Last updated: 2026-10-11 (fourth revision: three CI rounds diagnosed and fixed)_

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

### Run 2 (2026-10-11) — same failure, workflow file not actually updated

The workflow was edited (header comments removed) but line 33 still read
`android-actions/setup-android@v3`, so the run failed identically. Fixing a workflow file is the
one thing the automated agent cannot do: pushes touching `.github/workflows/**` are rejected with
"refusing to allow a GitHub App to create or update workflow … without `workflows` permission".

### Run 3 (2026-10-11) — SDK step passed, real build-script bug found

```
* Where: Build file 'app/build.gradle.kts' line: 14
* What went wrong: Script compilation error:
    if (keystorePropertiesFile.exists()) load(java.io.FileInputStream(keystorePropertiesFile))
                                                           ^ Unresolved reference: io
```

The workflow fix worked (the SDK, Node, Gradle and relay steps all ran). Past that, a genuine bug:
inside a Kotlin DSL script `java` resolves to the *Java plugin extension*, not the package, so a
fully qualified `java.io.FileInputStream` cannot be resolved. Fixed with a top-level
`import java.io.FileInputStream` and a comment recording why.

Three more problems found by re-reading the sources while the fix was being written, none of which
a static checker could see:

| Bug | Consequence | Fix |
| --- | --- | --- |
| `MainActivity` was a `ComponentActivity` but the app-lock code casts to `FragmentActivity` (required by `BiometricPrompt`) | turning the app lock on would have locked the user out permanently | it now extends `FragmentActivity` (still a `ComponentActivity`, so Compose is unaffected) |
| `displayNameOf(uri)` called a `Context` extension without a receiver | compile error, would have stopped the build again | `context.displayNameOf(uri)` |
| system Back was disabled inside a chat | Back closed the app instead of returning to the conversation list | the handler now covers every screen except Home |
| no escape from the lock screen when a phone has no biometrics or screen lock | app unusable after enabling the lock on such a device | "Turn off the app lock and continue" button |

`tools/static-checks/check_symbols.py` gained a check for platform/JDK types used without an
import — the same class of mistake as the `FileInputStream` bug, now caught locally.

### Run 4 (2026-10-11) — the manifest was in the wrong directory

Past the wrapper and the build script, Gradle reached the Android tasks and stopped:

```
> Task :app:processDebugMainManifest FAILED
property 'mainManifest' specifies file '.../app/src/main/AndroidManifest.xml' which doesn't exist
```

The manifest existed, but one directory too deep (`app/src/main/res/AndroidManifest.xml`). Android
expects exactly `app/src/main/AndroidManifest.xml`; `res/` holds resources only. Moved, and the
file itself parses with the service and both receivers in place.

Found via the annotation mechanism described below — no log copying needed.

### Reading a failed run without the raw log

GitHub's log and artifact downloads are not reachable from the environment that maintains this
repository, and the job page needs a signed-in session. `gradlew` therefore carries one small,
clearly marked addition: under GitHub Actions it tees the build output to a file, and on failure
prints the real compiler errors as `::error::` workflow commands. The runner turns those into
annotations on the check run, which *are* readable with the API (and show up inline in the Checks
tab for anyone looking at the pull request). Outside CI the wrapper behaves exactly as generated.

## First thing to do next

1. Update `.github/workflows/android.yml` — it still contains `android-actions/setup-android@v3`.
   Copy `docs/ci/android-workflow.yml.template` over it in the GitHub web editor. Automated pushes
   cannot touch workflow files, and the branch is otherwise fixed and ready.
2. The next run is the first real compile of the Android modules. Expect the class of problem a
   static checker cannot see: Compose compiler complaints, an Android API that needs a newer
   `compileSdk`, or a green-but-empty JUnit run.
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
