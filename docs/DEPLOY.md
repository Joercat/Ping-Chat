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

| Relay | Where | File |
| --- | --- | --- |
| Cloudflare Worker + Durable Objects | Cloudflare's free plan (no card, no server) | `server/cloudflare/` |
| Node.js | any computer you already have | `server/node/server.js` |

---

## Option A — Cloudflare Workers (recommended: free forever, nothing to maintain)

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

## Option B — the Node relay on hardware you already own

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

## Option C — a free-tier virtual machine

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

## Option D — no relay at all (LAN only)

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
| `https://ping-relay.you.workers.dev` | recommended |
| `http://192.168.1.20:8080` | allowed only for private addresses (`Allow plain HTTP for private addresses`) |
| empty | LAN-only mode |

The app checks the URL (no plain HTTP to the public internet, no redirects to another host),
registers the device's signed pre-key bundle, and reports any refusal by name. Two people do
not need the same relay to talk to each other: the mailbox lives on the relay that the *sender*
knows for that contact, which travels in the contact code. In practice, agree on one relay per
friend group so everybody can reach everybody.
