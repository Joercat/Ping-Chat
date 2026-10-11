# Ping

A small, private messenger for Android. No account, no phone number, no email address, and no
server that can read — or keep — your messages.

Ping talks to another phone directly when you are on the same Wi-Fi, and through a tiny relay
you can host for free when you are not. Everything that leaves the phone is sealed with a
signal-style Double Ratchet on top of an X3DH handshake; the relay only ever sees a mailbox id,
a kind byte and a length.

> **Status:** the Android app, the two relay implementations and the CI pipeline live in this
> repository. The crypto/protocol self-tests (387 assertions) and the relay end-to-end suite
> (49 assertions) pass; see [Verification](#verification) for how to re-run them.

---

## Install it on your phone

1. Get the APK. The pipeline in `.github/workflows/android.yml` is green and builds both APKs
   (tests included) on every push:
   * **From a branch:** GitHub → **Actions** → the newest green *Build, test & package* run →
     **Artifacts → `ping-apks`**. Inside are `Ping-2.0.0.apk` (install this one) and
     `Ping-2.0.0-debug.apk`.
   * **From `main`:** every push to `main` also publishes **Releases → latest**, so the phone can
     open `https://github.com/Joercat/Ping-Chat/releases/download/latest/Ping-2.0.0.apk` directly.
2. Open the APK on the phone and allow installing unknown apps once.
3. Optional but recommended: deploy the relay (three minutes, free) and paste its URL into
   **Settings → Relay address**. The quickest way is Render's free plan — Dashboard → **New +** →
   **Blueprint** → pick this repository; `render.yaml` does the rest (`docs/DEPLOY.md` has the
   walkthrough and the free-tier facts). Without a relay, Ping still works on the same Wi-Fi.

Details, signing, `adb install` and troubleshooting: [`docs/BUILD.md`](docs/BUILD.md).
Relay hosting options (Render free plan, Cloudflare free plan, a Raspberry Pi, a free VM, or
nothing at all): [`docs/DEPLOY.md`](docs/DEPLOY.md). A free host may sleep an idle instance; Ping
keeps it awake with a small health request every ten minutes while it runs, and waits out a cold
start when it does not.

> GitHub is used **only to build the APK** — the app never talks to it, and it is not a hosting
> platform. The relay runs on Cloudflare's free tier or on hardware you own, and the Node relay has
> no dependencies, so nothing here breaks if a free tier changes its terms (or GitHub does).

---

## What you get

* **End-to-end encrypted messages**, files and photos — X3DH (Ed25519 + X25519) handshake, then a
  Double Ratchet with ChaCha20-Poly1305, fresh keys per message, replay protection, and bounded
  skipped-key handling (so out-of-order delivery works without unbounded state).
* **Verify the person, not the server** — every chat shows a 60-digit safety number; mark a
  contact as verified and the app keeps warning you if their keys ever change.
* **Local first** — the whole conversation lives in an SQLCipher database whose key is sealed by
  the phone's secure hardware, with sensitive columns sealed a second time. Nothing is backed up
  off the device (`allowBackup=false`), and the panic wipe really does delete the keys.
* **Nice to use** — QR contact exchange, disappearing messages, reactions, edit/delete, read
  receipts and typing indicators (all optional), reply straight from a notification, an
  attachment picker, an optional biometric app lock, and a screenshot guard.
* **Two relays, one API** — a single-file Node.js relay and a Cloudflare Worker + Durable Object
  with identical behaviour. Both keep envelopes in RAM only and write nothing readable to disk.
* **Metadata hygiene** — envelopes are padded into fixed size buckets (256 B … 512 KB), sealed
  payloads carry no sender or recipient id, and the relay's only durable data is public pre-key
  material.

## How it works

```
   ┌────────────┐        sealed envelope (RAM only)        ┌────────────┐
   │  Phone A   │ ───────────────────────────────────────▶ │   relay    │
   │ SQLCipher  │ ◀─────────────────────────────────────── │ no storage │
   └────────────┘        poll / ack / long-poll             └────────────┘
          ▲                                                       ▲
          │        direct encrypted socket (mDNS, LAN)            │
          └───────────────────────────────────────────────────────┘
                             Phone B
```

1. Each phone generates an Ed25519 signing key and an X25519 key agreement key, plus a pool of
   one-time pre-keys, and publishes only the public parts.
2. A conversation starts with an X3DH handshake that proves the two key pairs belong together
   (the sender's binding signature is verified on receipt) and authenticates both sides.
3. Messages are then ratcheted: a new key per message, an extra DH step whenever either side
   replies, and the sender's header is authenticated as associated data.
4. The relay stores envelopes in memory until the receiving phone acknowledges them (or 7 days
   pass), and duplicates are dropped. Files travel in 64 KB chunks with per-chunk AEAD and a
   SHA-256 check at the end; plaintext never touches the disk.

## Repository layout

| Path | What it is |
| --- | --- |
| `app/src/main/java/com/ping/app/core/` | crypto primitives (X3DH, Double Ratchet, AEAD, HKDF, safety numbers) and the wire format — no Android dependencies, so it is unit-testable |
| `app/src/main/java/com/ping/app/data/` | SQLCipher database, Keystore-sealed vault, settings |
| `app/src/main/java/com/ping/app/engine/` | identity, sessions, relay client, LAN transport, attachments, the engine that ties them together |
| `app/src/main/java/com/ping/app/ui/` | Compose screens and the controller between UI and engine |
| `app/src/main/java/com/ping/app/service/` | foreground service, notifications with inline reply, boot receiver |
| `core/protocol/` | hand-written JSON + binary wire codec shared by the app and the tests |
| `server/node/` | zero-dependency Node relay |
| `server/cloudflare/` | the same relay as a Worker + Durable Object |
| `docs/` | build, deploy and CI instructions |

## Verification

```bash
tools/selftest/run.sh                                 # 387 crypto/protocol checks, no SDK needed
node server/node/server.js --port 8080 &
PING_RELAY=http://127.0.0.1:8080 tools/selftest/run.sh --relay    # + 49 live relay checks
```

* The crypto/protocol suite covers the handshake, ratchet (including out-of-order and
  post-compromise recovery), codec, padding buckets, invite codes and safety numbers.
* The relay suite walks a full two-device conversation through `POST /v1/send`, `/v1/poll`,
  `/v1/ack` and the pre-key exchange, then tries to break the relay: forged signatures, a
  hijacked mailbox, a replayed nonce, malformed input. **Both relays pass the same 49 checks**,
  which is what keeps them drop-in interchangeable.
* In CI the same suites run as JUnit (`./gradlew testDebugUnitTest`) against a Node relay that
  the workflow starts first.
* `docs/STATUS.md` records what has been verified, what has not, and what to do next.

## Security notes and limits

* The relay is the only third party, and it is optional: without one, Ping is a LAN messenger.
  With one, it learns mailbox ids and envelope sizes, nothing else.
* If a contact's keys change, the safety number changes. Ping shows the warning; only re-verify
  after talking to the person out of band.
* Disappearing messages are a convenience, not a guarantee: the other side's phone can always
  screenshot before the timer runs out (the screenshot *guard* on your own device is a separate
  setting).
* Deleting a contact or conversation deletes local data. There is no cloud copy to delete.
* No app is immune to a compromised phone; Ping keeps its keys in the Android Keystore and
  refuses to read a database that was restored from a backup, which covers the common cases.

## Licence

No licence file is included yet — treat the repository as "all rights reserved" until one is
added.
