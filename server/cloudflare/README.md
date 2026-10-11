# Ping relay on Cloudflare Workers (free, forever-ish)

This is the "no computer at home" deployment of the Ping relay. It runs on Cloudflare's
**free plan**, needs no credit card and no always-on machine, and it keeps every promise the
Node relay makes:

| Property | How |
| --- | --- |
| **Messages are never stored** | Envelopes live in RAM inside a Durable Object and are deleted the moment the phone acknowledges them (or after 7 days). Nothing encrypted-to-the-recipient is ever written to disk. |
| **The relay cannot read anything** | Envelopes are sealed on the sender's phone (X3DH + Double Ratchet, ChaCha20-Poly1305). The relay sees a mailbox id, a kind byte and a length. |
| **No accounts, no logs of who talks to whom** | There is no user table. A mailbox is a hash of the owner's public key. |
| **Disk contents are public data only** | The only thing written to storage is public pre-key bundles (public keys + signatures). That is what lets a message reach you while your phone is offline. |
| **Nothing to leak on shutdown** | Delete the worker and nothing readable remains. |

## Deploy it (about three minutes)

```bash
# 1. install wrangler once
npm install -g wrangler          # or: npm install   (uses the local devDependency)

# 2. log in to Cloudflare (opens a browser; the free account is enough)
wrangler login

# 3. from this directory
cd server/cloudflare
npm install
npx wrangler deploy
```

`wrangler deploy` prints something like:

```
https://ping-relay.<your-subdomain>.workers.dev
```

Paste that URL into the app: **Settings → Relay address → Save and test**. The app answers
"Relay answered and accepted this device's keys" when it is working.

Check it from a browser or `curl`: `https://…workers.dev/v1/health` returns

```json
{"ok":true,"runtime":"cloudflare-workers","policy":{"storesMessagesOnDisk":false}}
```

## Cost

The Workers free plan includes 100 000 requests/day and Durable Objects with SQLite storage
(no card required). A phone that stays connected polls roughly once every 30 seconds, so a
single relay handles a large group of friends long before the daily limit matters. If you ever
hit it, the app simply retries, and you can deploy the same worker a second time under a
different name.

## What is intentionally missing

* No push notifications through Google/Apple servers: Ping keeps its own long poll so message
  metadata never leaves the encrypted channel. That is also why the phone shows a quiet
  "connected" notification while the app is in the background.
* No message history on the server — by design. If both phones are wiped, the conversation is
  gone; that is the trade the project makes.

## Troubleshooting

| Symptom | Fix |
| --- | --- |
| `wrangler deploy` says `Missing account id` | run `wrangler login` again |
| App says "relay refused this device's keys" | the URL is wrong or it is not the Ping relay — check `/v1/health` |
| App says "plain http is only allowed for private addresses" | use the `https://…workers.dev` URL, not `http://…` |
| Messages stay "queued" for a long time | open the app once so the phone registers its pre-keys; both sides must have been online at least once |
