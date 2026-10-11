# Running the relay — free, and for as long as you want

Ping works without any server when both people are on the same Wi-Fi. To reach each other from
anywhere you need a **relay**: a small program that forwards sealed envelopes between two
mailboxes. It never sees a message, a name, or a contact list, and it never writes a message to
disk.

You only need to understand one thing before picking an option:

> The relay stores **public pre-key bundles** (public keys + signatures) so that a message can
> reach a phone that is offline. Everything else — every message, every attachment, every
> reaction — is either in RAM for a few seconds or already on the phones.

Two relays ship in this repository and they speak exactly the same HTTP API, so you can switch
between them at any time without touching the app:

> **GitHub is not a host here, and never will be.** The repository is only used to *build* the APK.
> Nothing about the running app is tied to GitHub: the relay runs on Cloudflare's free tier, on a
> Raspberry Pi, or on a VM you already pay for — and if every free tier changed its terms tomorrow,
> the zero-dependency Node relay keeps working on hardware you own. A GitHub-hosted relay would be
> exactly the kind of always-on third-party dependency this project avoids, and repositories,
> Actions minutes and releases are not a hosting product.

| Relay | Where | File |
| --- | --- | --- |
| Node.js on **Render** | Render's free plan, deploy from this repository in one click | `render.yaml` + `server/node/` |
| Cloudflare Worker + Durable Objects | Cloudflare's free plan (no card, no server) | `server/cloudflare/` |
| Node.js | any computer you already have | `server/node/server.js` |

| Free host | Sleeps after | Wake-up time | Monthly allowance | Note |
| --- | --- | --- | --- | --- |
| Render free web service | 15 min without traffic | ~1 min | 750 instance hours per workspace | the app's keep-alive keeps it awake while Ping runs |
| Cloudflare Workers free | never | none | 100k requests/day | edge-native, no container to wake |

Neither host can read anything: the relay only ever forwards sealed envelopes and holds them in
RAM.

---

## Option A — Render, free plan (the one-click one)

Render runs the zero-dependency Node relay as-is. The blueprint in `render.yaml` is already in this
repository, so there is nothing to write:

1. Create a free account at [render.com](https://render.com) (no card required).
2. Dashboard → **New +** → **Blueprint** → pick this repository (`Joercat/Ping-Chat`) → **Apply**.
   Render reads `render.yaml`, sets the start command, the health check and the environment, and
   starts the service. The first build takes a couple of minutes.
3. When it is live, copy the service URL from the top of the page —
   `https://ping-relay-XXXX.onrender.com`.
4. In Ping: **Settings → Relay address**, paste the URL, press **Save and test**. Ping publishes its
   keys and reports “Connected to the relay”.
5. Add a contact and send a message. That is the whole setup.

**What the free plan means, honestly**

* A free web service **spins down after 15 minutes without traffic** and takes about a minute to
  come back. Ping is built for that: while the app (or its foreground service) is running it sends
  `GET /v1/health` at least every ten minutes — the switch is **Settings → Keep the relay awake**,
  on by default — so the relay stays up while your phone is on. When nothing is pinging, the first
  message simply takes longer: the app waits up to 90 seconds for the instance to boot instead of
  declaring the relay offline.
* **750 instance hours per workspace per month.** A service that is awake 24/7 uses ~720 of them,
  so keep this the only free web service in the workspace if you want it always warm. Spun-down
  time is not counted.
* The **filesystem is wiped on every deploy and restart**, and Render may restart free instances at
  any time. That is fine here: envelopes are in RAM by design, so a restart can only drop an
  undelivered envelope — the sender's outbox notices the missing acknowledgement and sends it again
  (messages are only marked delivered once the other phone acknowledges them). The only thing that
  would be lost is the public pre-key material in `/tmp`, which Ping re-publishes automatically.
* Bandwidth and build minutes are metered as well, but a text conversation moves a few kilobytes
  per message; the free allowances are far away unless you send GBs of attachments.
* If you want the relay warm even when your phone is off, point any uptime pinger
  ([cron-job.org](https://cron-job.org), UptimeRobot — free) at
  `https://your-service.onrender.com/v1/health` every 10 minutes. That is optional: it keeps the
  first message of the day instant instead of “about a minute”.

**Verifying the deployment** (any host, from a computer):

```bash
curl -s https://your-service.onrender.com/v1/health
# {"ok":true,"version":1,…,"policy":{"storesMessagesOnDisk":false,"diskContents":"public pre-key bundles only"}}
```

`storesMessagesOnDisk` must be `false`; that is the promise, in the relay's own words.

---

## Option B — Cloudflare Workers (free forever, nothing to maintain)

Cloudflare's free plan covers this relay comfortably and needs no credit card. Follow
[`../server/cloudflare/README.md`](../server/cloudflare/README.md); the short version is:

```bash
cd server/cloudflare
npm install
npx wrangler login
npx wrangler deploy
```

Then paste the printed `https://ping-relay.<you>.workers.dev` into **Settings → Relay address**
in the app and tap **Save and test**.

Why this is the default recommendation:

* no machine of yours has to stay on;
* Durable Objects keep their in-memory state while the region is warm, and the free plan includes
  SQLite-backed storage for the pre-key bundle;
* the free tier allows 100 000 requests/day. One phone uses roughly 2 900 requests/day while it
  is connected, so a handful of friends share one relay for free;
* if it is ever rate-limited, nothing breaks — senders keep the message in the outbox and retry.

**Verify it** with a browser: `https://…workers.dev/v1/health` must answer

```json
{"ok":true,"runtime":"cloudflare-workers","policy":{"storesMessagesOnDisk":false}}
```

---

## Option C — the Node relay on hardware you already own

Perfect if you have a Raspberry Pi, a home server, a NAS, or an old Android phone running
Termux. It is a single file with **no dependencies at all** (not even npm packages):

```bash
cd server/node
node server.js --port 8080 --host 0.0.0.0 --data ~/ping-relay.json
```

The app speaks plain HTTP only to private addresses; on a public host it insists on HTTPS. To
get a public HTTPS name for a machine at home, use one of these (both have free tiers):

* **Cloudflare Tunnel** — `cloudflared tunnel --url http://localhost:8080`, no account needed for
  a quick tunnel, account needed for a stable name;
* **Tailscale Funnel** — free for personal use, gives you an HTTPS name inside your tailnet.

Even simpler: keep it on the LAN, put the phone on the same Wi-Fi, and never expose anything.

Notes:

* `--data` holds the pre-key skeleton file. Deleting it just means everybody re-registers.
* The Node relay keeps envelopes in RAM, wipes them on shutdown (SIGINT/SIGTERM), and prints
  `storesMessagesOnDisk:false` in `/v1/health`.

---

## Option D — a free-tier virtual machine

Oracle Cloud's *Always Free* ARM VM, Fly.io's free allowance, or any small VPS works: run the
Node relay behind Caddy or nginx for automatic HTTPS. This is the most "traditional" path and
the one to pick if you want a stable hostname you control. Free tiers change over time, so check
the current terms before relying on one — the relay is tiny (a few MB of RAM, no database) and
will run happily on the smallest instance.

If you use a VM, harden it the boring way: SSH keys only, a firewall that allows just 443, and
`systemd` to restart the relay after a reboot:

```ini
# /etc/systemd/system/ping-relay.service
[Unit]
Description=Ping relay
After=network-online.target

[Service]
ExecStart=/usr/bin/node /opt/ping-relay/server.js --port 8080 --host 127.0.0.1
Restart=always
User=ping

[Install]
WantedBy=multi-user.target
```

Then point Caddy at it (`ping.example.com { reverse_proxy 127.0.0.1:8080 }`) and run
`systemctl enable --now ping-relay caddy`.

---

## Option E — no relay at all (LAN only)

Leave **Relay address** empty in the app. Ping then uses mDNS discovery and a direct encrypted
socket on the local network, which is all you need at home, in an office, or in a café where
both phones are on the same Wi-Fi. This is also the fastest path: no third party, no latency.

---

## What a relay can and cannot do (read this once)

**Cannot**

* read a message, an attachment, a name, a group, a reaction, or a typing indicator — all of it is
  sealed on the sending phone;
* learn who talks to whom from message contents — the envelope carries a rotating per-session tag
  and a padded length;
* keep a copy of a message: envelopes are deleted when the receiving phone acknowledges them, and
  after 7 days at the latest;
* recover anything after a fight with a disk — messages are not stored on disk at all.

**Can / does**

* store the mailbox's public pre-key bundle (`/v1/prekeys`), which is public data by definition;
* see that some mailbox polled, and how big an envelope was (padded to 256 B…512 KB buckets, so
  the size tells you almost nothing);
* be temporarily down. Senders retry with backoff for a while and keep the message in the local
  outbox if the relay is unreachable.

If you want to prove those claims, read `server/node/server.js` or
`server/cloudflare/src/index.js` — both are short, comment-heavy, and share the same layout.

---

## Pointing the app at a relay

Same place in both build variants: **Settings → Relay address**, then **Save and test**:

| Relay URL | Result |
| --- | --- |
| `https://ping-relay-XXXX.onrender.com` | recommended (deploy with `render.yaml`) |
| `https://ping-relay.you.workers.dev` | also recommended (Cloudflare free plan) |
| `http://192.168.1.20:8080` | allowed only for private addresses (`Allow plain HTTP for private addresses`) |
| empty | LAN-only mode |

The app checks the URL (no plain HTTP to the public internet, no redirects to another host),
registers the device's signed pre-key bundle, and reports any refusal by name. It also waits up to
90 seconds for the first answer, so a host that has put an idle relay to sleep still works: the
request itself wakes the instance.

**Keep the relay awake.** With **Settings → Keep the relay awake** on (the default), Ping sends
`GET /v1/health` whenever it has been quiet for ten minutes — while the app or its foreground
service runs — which is the traffic a free host like Render counts as activity, so the instance
never reaches its spin-down timer. The screen shows *Relay last answered: …* so you can see it
happening. Turn it off and the relay simply takes about a minute to wake when you next use it; it
cannot cost you a message, because an envelope is only dropped by the sender's outbox after the
other phone acknowledges it. Two people do
not need the same relay to talk to each other: the mailbox lives on the relay that the *sender*
knows for that contact, which travels in the contact code. In practice, agree on one relay per
friend group so everybody can reach everybody.
