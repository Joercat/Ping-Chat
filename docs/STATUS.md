# Project status

_Last updated: 2026-10-11 (eighth revision: group chats with sender-key encryption, a mobile-first
round of UI and reliability fixes, one signed-for-updates APK, and the project relay as the default)_

## What is verified, and how

| Layer | State | How it was checked |
| --- | --- | --- |
| `core/crypto` + `core/protocol` | **401 checks pass** | `tools/selftest/run.sh`, and `./gradlew testDebugUnitTest` in CI (`SelfTests.kt`) |
| Relay API (`server/node`, `server/cloudflare`) | **49 checks pass against both** | `tools/selftest/run.sh --relay` with a relay running locally |
| Cloudflare worker packaging | **deploys** | `wrangler deploy --dry-run` (bundle 15.7 KiB, `MAILBOX` binding present) |
| SQL in `data/` + `engine/` | **schema-consistent** | `python3 tools/static-checks/check_sql_schema.py` (10 tables, 59 columns) |
| Kotlin symbols across modules | **consistent** | `python3 tools/static-checks/check_symbols.py` (imports, receivers, constructors) |
| Gradle wrapper | **usable** | `gradle-wrapper.jar` is a valid archive (33 entries, `GradleWrapperMain` present), `gradlew` is the genuine 8.x script, `distributionUrl` = gradle-8.7-bin.zip |
| Android resources | **well-formed** | every XML parses, all vector drawables carry the four required attributes, `R.*` references resolve, adaptive icons cover API 26+ |
| Android app compile | **builds** | `./gradlew assembleDebug testDebugUnitTest assembleRelease` in CI (run 38177242537, commit `261e134`) — every step green, `Ping-2.0.0.apk` + `Ping-2.0.0-debug.apk` uploaded as the `ping-apks` artifact |
| Render blueprint (`render.yaml`) | **validated** | the Node relay started with Render's environment (`PORT`, `HOST`, `PING_DATA` on the ephemeral `/tmp`) answers `GET /v1/health` with `"storesMessagesOnDisk":false`; `node --check server.js` parses |
| Settings persistence | **all 18 fields loaded and saved** | `python3 tools/static-checks/check_settings.py --strict` (with its own self-test) |
| Static type checking | **clean** | `check_symbols.py`, `check_calls.py`, `check_types.py --strict`, `check_sql_schema.py` — all zero findings, and `check_types.py`'s own `--self-test` passes |

The Android modules (`ui`, `service`, `di`, `engine`, `data`) were written without a compiler in
the environment that produced them, so the static checkers above exist as a safety net. They catch
renamed members, missing imports, schema drift, wrong argument counts and the two type errors that
actually slipped through (`check_types.py`), and since run 5 the real gate — `assembleDebug` in
CI — is green as well.

### Getting the APK

* **From this branch:** GitHub → **Actions** → the newest green *Build, test & package* run →
  **Artifacts → `ping-apks`**. Unzip it and install `Ping-2.0.0.apk` (the release build; it is
  signed with the debug key, which is fine for sideloading). `Ping-2.0.0-debug.apk` is identical
  apart from debug flags.
* **From `main`:** every push to `main` also publishes a rolling **Releases → latest**, so the
  newest APK always sits at
  `https://github.com/Joercat/Ping-Chat/releases/download/latest/Ping-2.0.0.apk` — a link the phone
  can open directly.

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

### Run 8 (2026-10-11) — group chats, and three more silent-failure classes closed

**Groups** (`core/crypto/GroupCrypto.kt`, wire kinds 11/12, `groups`/`group_members`/`group_state`
tables, `conversations.group_id`, `NewGroupScreen`/`GroupInfoScreen`, group-aware `ChatScreen`) use
one epoch key per group, delivered to each member inside their own pairwise ratchet — the relay sees
ordinary sealed envelopes and learns nothing about the group. Each message gets its own key and nonce
from `HKDF(epochKey, "PingGroup/v1" ‖ senderSigningKey ‖ counter)`, is signed by the sender's Ed25519
identity and verified before decryption, carries the group id and epoch in the AAD, and is refused if
the counter is not higher than the last one seen from that sender (replay) or if the blob's sender key
is not the key of the peer the ratchet delivered it from (forgery). Counters are `max(now, last + 1)`
and persisted, so they survive restarts and a clock that jumps backwards. Adding or removing a member
rotates the key, so a newcomer cannot read the backlog and a leaver cannot read on.

**Attachments stopped being a dead end.** A received file was shown as an inert chip: it could not
be opened, saved or shared. Tapping now opens the decrypted copy in whatever app handles its type,
long pressing offers Open / Save to phone / Share, and the inline image preview opens too. The
decrypted copy exists only because Android cannot pass bytes to another app any other way: it goes to
the app's private cache, is handed out through a FileProvider URI that grants read access to the one
app the user picked, and `AttachmentStore.clearExports()` deletes every copy on the next start.

Deliberate limits, stated in the file so nobody has to guess: one shared key per epoch means a
compromised device exposes the epochs it holds (per-sender chains would narrow this), group
attachments are not implemented yet, and a removed member simply receives nothing more.

**Three checker bugs, each of which had hidden real errors:**

* `check_symbols.py` never checked that a project type is imported: `GroupRecord` (in `data`) used
  from `engine` and `ui` compiled only after the import was added — this is the same
  "unresolved reference" that reached CI once. The new rule parses column-0 declarations only, so a
  nested `Screen.Contact` no longer shadows the real `Contact`, and it understands nullable generics
  (`List<GroupRecord?>`), which its first version missed.
* `check_types.py` inferred the type of `graph.onDb { … }.id` as the *call's* type, so it reported a
  type error that did not exist. A trailing property access now means "unknown", with a regression
  case in the self-test.
* `check_settings.py` counted braces to find `read()`/`persist()`, and an expression body containing
  a `when` block truncated the member — every field after it looked unpersisted. It now reads class
  members by indentation.

### Run 7 (2026-10-11) — the app was slow, lost names, flashed, stalled transfers, and would not update

Five complaints, five separate causes, all found by reading the code rather than guessing:

1. **Names vanished and lists flashed.** `AppGraph.live` built a *new* flow on every recomposition,
   so `collectAsState(initial = null)` reset the screen to its empty value each time and then filled
   in again. It now memoises one flow per key in a `ConcurrentHashMap`; every call site passes a key
   (`messages:<id>`, `contact:<hash>`, `conversations`, …). This was the single biggest quality bug in
   the app, and it is why the UI felt like it was fighting the user.
2. **The chat jumped to the top.** `animateScrollToItem` ran on every recomposition; it now jumps to
   the newest message when the chat opens and only animates when the reader is within three items of
   the bottom.
3. **Sends gave up.** The outbox retried six times over roughly two minutes, then deleted the session
   and marked the message failed — a phone that was asleep or on a flaky network lost the send. It now
   retries for as long as the message is wanted (60 attempts, exponential backoff capped at 15
   minutes), distinguishes a permanent relay rejection from a recoverable one, and wakes a spun-down
   relay before trying.
4. **Attachments stalled and could never be resent.** Every chunk was fired in one burst, and the
   first failure aborted the transfer with nothing left on disk to resume from. The announcement and
   all remaining chunks now go through the same persistent outbox, so a transfer survives a restart,
   resumes automatically, and a 500-envelope mailbox no longer makes a 5 MB file impossible.
5. **"App not installed" on update.** CI signed every build with a *fresh* debug keystore, so each
   release had a different signature and Android refused to update over the previous one. The project
   now ships a keystore (`app/keystore/ping.p12`, documented in `app/keystore/README.md`) that signs
   both debug and release builds, so updates install over each other; `versionCode` 3 / `2.1.0`.

The built-in relay is now `https://ping-relay-tvj1.onrender.com`, pre-filled in onboarding, with a
one-time migration for installs that stored an empty address (a deliberate choice still wins).

### Run 6 (2026-10-11) — free-tier hosting: Render blueprint and a relay that stays awake

Render's free plan runs the zero-dependency Node relay directly, so the repository now contains
`render.yaml` (Dashboard → New + → Blueprint → Apply) and the relay got what a sleeping host needs:

* **`RelayClient.probe(wake = true)`** waits up to 90 seconds for an answer, which is the cold start
  a free instance needs. It replaced the 45-second read timeout for exactly the calls that may have
  to wake somebody up: the first poll, the first registration, and the keep-alive itself. `probe()`
  also checks the protocol version, so “connected” means the relay can actually be talked to.
* **`keepRelayAwake()`** sends `GET /v1/health` whenever the relay has been quiet for ten minutes —
  the traffic Render counts as activity, so a free instance never reaches its 15-minute spin-down
  timer. It runs in the maintenance loop, independently of polling, so it keeps working while
  polling backs off after an error. **Settings → Keep the relay awake** turns it off, and
  *Relay last answered: …* shows that it works.
* The honest limits are in `docs/DEPLOY.md`: 750 instance hours per workspace per month (~720 for a
  service that is awake around the clock), a filesystem that is wiped on every deploy, and the fact
  that Render may restart free instances at any time. None of it can cost a message: envelopes are
  in RAM by design, so a restart can only drop an undelivered envelope, and the sender's outbox
  re-sends it because messages are only marked delivered once the other phone acknowledges them.
* Two more checker bugs were found and fixed while doing this, both of which had been hiding real
  errors: `check_calls.py` skipped *every* uppercase-initial call (all Compose composables and all
  constructors) and it dropped `private val` parameters from constructors; it now also prefers a
  declaration in the same file and ignores names the file imports from elsewhere, which is what
  keeps Compose's `Text` from being compared with `Wire.Payload.Text`. A new
  `check_settings.py` covers the last silent-failure class: a setting added to the data class but
  forgotten in `read()` or `persist()`.

### Run 5 (2026-10-11) — the first real Kotlin errors

The manifest fix worked and the run reached `:app:compileDebugKotlin`. Six errors, all in
`engine/Engine.kt`, all real bugs rather than configuration:

| Line | Error | What it was |
| --- | --- | --- |
| 498, 741, 753, 762 | `Type mismatch: inferred type is Contact but String was expected` | `sendEphemeral(contact, …)` where the parameter is `peerHash: String` — the four call sites meant `contact.hash` |
| 315, 548 | `Suspension functions can be called only within coroutine body` | `update { it.copy(queued = graph.onDb { … }) }` — `update` takes a plain lambda, so the suspend `onDb` call had to move outside it |

Both classes of mistake are now caught *before* a build, by a new checker:

```bash
python3 tools/static-checks/check_types.py --strict   # also runs its own self-test
```

`check_types.py` follows declared return types, scopes, parameters, properties (including
`get() =` and `by lazy` forms) and lambda regions, and reports two things: an object passed where a
Kotlin builtin is expected (or the reverse), and a suspend call inside a lambda that is not
`inline` and not declared `suspend`. It is deliberately conservative — a call whose receiver or
declaration it cannot resolve is simply not judged — because a false finding costs a build cycle.
`--self-test` runs both rules over a snippet containing the two bugs from this run plus four
look-alikes, so the checker cannot silently stop working. Reintroducing the six errors above makes
it report exactly six findings, and it is clean on the tree as it stands.

## Reading a failed run without the raw log

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
python3 tools/static-checks/check_calls.py --strict
python3 tools/static-checks/check_types.py --strict
python3 tools/static-checks/check_settings.py --strict

# the real build
./gradlew assembleDebug testDebugUnitTest
```
