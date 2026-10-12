/**
 * Ping relay — Cloudflare Workers + Durable Objects.
 *
 * Free-plan friendly, no credit card, no always-on server to babysit:
 *
 *   npm install -g wrangler
 *   wrangler login
 *   cd server/cloudflare
 *   wrangler deploy
 *
 * WHAT IT STORES
 *   Durable Object storage (persistent): public pre-key bundles only — public keys and their
 *   signatures. This is what lets somebody reach you while your phone is offline.
 *   Memory (never written anywhere): encrypted envelopes, until the recipient acknowledges them
 *   or their TTL expires. A Durable Object eviction erases them, and that is fine: senders retry.
 *
 * WHAT IT CAN READ
 *   Nothing. Envelopes are sealed on the sender's device with keys derived from an X3DH handshake
 *   and a Double Ratchet. The relay sees a mailbox id, a kind byte and a length.
 */

const VERSION = 1;
const AUTH_PREFIX = 'PingRelayAuth/v1';
const MAILBOX_PREFIX = 'PingMailbox/v1';

const LIMITS = {
  envelopeTtlMs: 7 * 24 * 60 * 60 * 1000,
  nonceTtlMs: 60_000,
  maxEnvelopeBytes: 512 * 1024,
  maxEnvelopesPerMailbox: 500,
  maxQueueBytesPerMailbox: 16 * 1024 * 1024,
  maxPreKeysPerMailbox: 200,
  maxBodyBytes: 640 * 1024,
  pollMaxWaitMs: 30_000,
};

// ---------------------------------------------------------------------------- encoding helpers

const textEncoder = new TextEncoder();

function base64urlToBytes(text) {
  if (typeof text !== 'string' || text.length > 800_000 || !/^[A-Za-z0-9_-]*$/.test(text)) {
    throw new Error('bad_base64url');
  }
  const padded = text.replace(/-/g, '+').replace(/_/g, '/');
  const binary = atob(padded + '='.repeat((4 - (padded.length % 4)) % 4));
  const bytes = new Uint8Array(binary.length);
  for (let i = 0; i < binary.length; i += 1) bytes[i] = binary.charCodeAt(i);
  return bytes;
}

function bytesToBase64url(bytes) {
  let binary = '';
  const view = new Uint8Array(bytes);
  for (let i = 0; i < view.length; i += 1) binary += String.fromCharCode(view[i]);
  return btoa(binary).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}

function concatBytes(parts) {
  const total = parts.reduce((n, part) => n + part.length, 0);
  const out = new Uint8Array(total);
  let offset = 0;
  for (const part of parts) {
    out.set(part, offset);
    offset += part.length;
  }
  return out;
}

function uint32Be(value) {
  const buffer = new Uint8Array(4);
  new DataView(buffer.buffer).setUint32(0, value >>> 0);
  return buffer;
}

function uint64Be(value) {
  const buffer = new Uint8Array(8);
  new DataView(buffer.buffer).setBigUint64(0, BigInt(value));
  return buffer;
}

async function sha256Bytes(bytes) {
  return new Uint8Array(await crypto.subtle.digest('SHA-256', bytes));
}

async function mailboxIdFor(dhIdentityKey) {
  const digest = await sha256Bytes(concatBytes([textEncoder.encode(MAILBOX_PREFIX), dhIdentityKey]));
  return bytesToBase64url(digest.subarray(0, 16));
}

async function verifyEd25519(rawKey, message, signature) {
  if (rawKey.length !== 32 || signature.length !== 64) return false;
  try {
    const key = await crypto.subtle.importKey('raw', rawKey, { name: 'Ed25519' }, false, ['verify']);
    return await crypto.subtle.verify('Ed25519', key, signature, message);
  } catch (err) {
    return false;
  }
}

/** Reads only the routing header of an envelope: version, kind and mailbox. */
function envelopeMailbox(bytes) {
  if (bytes.length < 18 || bytes.length > LIMITS.maxEnvelopeBytes) throw new Error('bad_envelope');
  if (bytes[0] !== VERSION) throw new Error('bad_envelope');
  if (bytes[1] !== 0 && bytes[1] !== 1) throw new Error('bad_envelope');
  return bytesToBase64url(bytes.subarray(2, 18));
}

async function envelopeId(bytes) {
  const digest = await sha256Bytes(bytes);
  return bytesToBase64url(digest.subarray(0, 16));
}

async function json(data, status = 200) {
  return new Response(JSON.stringify(data), {
    status,
    headers: {
      'content-type': 'application/json; charset=utf-8',
      'cache-control': 'no-store',
      'x-content-type-options': 'nosniff',
    },
  });
}

// ---------------------------------------------------------------------------- durable object

export class Mailbox {
  constructor(state, env) {
    this.state = state;
    this.env = env;
    /** Encrypted envelopes live here and nowhere else. */
    this.envelopes = [];
    this.queueBytes = 0;
    this.waiters = [];
    this.nonces = new Map();
    this.skeleton = null;
    this.preKeys = [];
    this.mailboxId = '';
    this.loaded = state.blockConcurrencyWhile(async () => {
      // Only public key material is read from storage. Envelopes are never persisted.
      this.skeleton = (await state.storage.get('skeleton')) || null;
      this.preKeys = (await state.storage.get('preKeys')) || [];
      const alarm = await state.storage.getAlarm();
      if (alarm === null) await state.storage.setAlarm(Date.now() + LIMITS.envelopeTtlMs);
    });
  }

  /** Periodic sweep: drop expired envelopes and stale nonces. */
  async alarm() {
    this.sweep();
    await this.state.storage.setAlarm(Date.now() + 60 * 60 * 1000);
  }

  sweep() {
    const now = Date.now();
    const kept = [];
    let bytes = 0;
    for (const entry of this.envelopes) {
      if (now - entry.receivedAt > LIMITS.envelopeTtlMs) continue;
      kept.push(entry);
      bytes += entry.data.length;
    }
    this.envelopes = kept;
    this.queueBytes = bytes;
    for (const [nonce, entry] of this.nonces) if (entry.expiresAt < now) this.nonces.delete(nonce);
  }

  issueNonce(mailboxId) {
    const nonce = bytesToBase64url(crypto.getRandomValues(new Uint8Array(32)));
    this.nonces.set(nonce, { mailbox: mailboxId, expiresAt: Date.now() + LIMITS.nonceTtlMs });
    return nonce;
  }

  consumeNonce(nonce, mailboxId) {
    const entry = this.nonces.get(nonce);
    if (!entry) return false;
    this.nonces.delete(nonce); // single use
    if (entry.expiresAt < Date.now()) return false;
    return entry.mailbox === mailboxId;
  }

  async verifyAuth(body, action) {
    let signature;
    try {
      signature = base64urlToBytes(String(body.signature || ''));
    } catch (err) {
      return { ok: false, code: 'missing_fields' };
    }
    const mailboxId = String(body.mailbox || '');
    const nonce = String(body.nonce || '');
    if (!mailboxId || !nonce) return { ok: false, code: 'missing_fields' };
    if (!this.skeleton) return { ok: false, code: 'unknown_mailbox' };
    if (!this.consumeNonce(nonce, mailboxId)) return { ok: false, code: 'bad_nonce' };
    const message = concatBytes([
      textEncoder.encode(AUTH_PREFIX),
      textEncoder.encode(action),
      base64urlToBytes(mailboxId),
      base64urlToBytes(nonce),
    ]);
    const signingKey = base64urlToBytes(this.skeleton.signingKey);
    if (!(await verifyEd25519(signingKey, message, signature))) return { ok: false, code: 'bad_signature' };
    return { ok: true };
  }

  async fetch(request) {
    await this.loaded;
    const url = new URL(request.url);
    const path = url.pathname;
    const method = request.method;

    let body = null;
    if (method === 'POST') {
      const text = await request.text();
      if (text.length > LIMITS.maxBodyBytes) return json({ ok: false, error: 'body_too_large' }, 413);
      try {
        body = text ? JSON.parse(text) : {};
      } catch (err) {
        return json({ ok: false, error: 'bad_json' }, 400);
      }
    }

    const mailboxId = method === 'POST' ? String(body.mailbox || '') : url.searchParams.get('mailbox') || '';
    // Remembered so that issued nonces can be bound to this mailbox.
    if (mailboxId) this.mailboxId = mailboxId;

    if (path === '/v1/register' && method === 'POST') {
      return this.handleRegister(body, mailboxId);
    }
    if (path === '/v1/bundle' && method === 'GET') {
      return this.handleBundle(mailboxId);
    }
    if (path === '/v1/send' && method === 'POST') {
      return this.handleSend(body);
    }
    if (path === '/v1/challenge' && method === 'GET') {
      if (!this.skeleton) return json({ ok: false, error: 'unknown_mailbox' }, 404);
      return json({ ok: true, nonce: this.issueNonce(mailboxId) });
    }
    if (path === '/v1/poll' && method === 'POST') {
      const auth = await this.verifyAuth(body, 'poll');
      if (!auth.ok) return json({ ok: false, error: auth.code }, auth.code === 'unknown_mailbox' ? 404 : 401);
      const waitMs = Math.min(Number(body.waitMs || 0) || 0, LIMITS.pollMaxWaitMs);
      if (this.envelopes.length > 0 || waitMs <= 0) return this.pollResponse();
      return await this.waitForEnvelope(waitMs);
    }
    if (path === '/v1/ack' && method === 'POST') {
      const auth = await this.verifyAuth(body, 'ack');
      if (!auth.ok) return json({ ok: false, error: auth.code }, auth.code === 'unknown_mailbox' ? 404 : 401);
      const ids = new Set(Array.isArray(body.ids) ? body.ids.map(String) : []);
      const before = this.envelopes.length;
      this.envelopes = this.envelopes.filter((entry) => !ids.has(entry.id));
      this.queueBytes = this.envelopes.reduce((n, entry) => n + entry.data.length, 0);
      return json({ ok: true, removed: before - this.envelopes.length });
    }
    if (path === '/v1/prekeys' && method === 'POST') {
      const auth = await this.verifyAuth(body, 'keys');
      if (!auth.ok) return json({ ok: false, error: auth.code }, auth.code === 'unknown_mailbox' ? 404 : 401);
      const incoming = Array.isArray(body.oneTimePreKeys) ? body.oneTimePreKeys : [];
      for (const key of incoming) {
        if (!key || typeof key.key !== 'string' || key.key.length > 128) continue;
        if (!Number.isInteger(Number(key.id))) continue;
        if (this.preKeys.length >= LIMITS.maxPreKeysPerMailbox) break;
        if (this.preKeys.some((existing) => existing.id === Number(key.id))) continue;
        this.preKeys.push({ id: Number(key.id), key: key.key });
      }
      await this.state.storage.put('preKeys', this.preKeys);
      return json({ ok: true, preKeys: this.preKeys.length, nextNonce: this.issueNonce(mailboxId) });
    }
    return json({ ok: false, error: 'not_found' }, 404);
  }

  async handleRegister(body, mailboxId) {
    let parsed;
    try {
      parsed = {
        signingKey: base64urlToBytes(body.signingKey),
        dhIdentityKey: base64urlToBytes(body.dhIdentityKey),
        dhBinding: base64urlToBytes(body.dhBinding),
        signedPreKey: base64urlToBytes(body.signedPreKey),
        signedPreKeySignature: base64urlToBytes(body.signedPreKeySignature),
        signedPreKeyId: Number(body.signedPreKeyId || 0) | 0,
        publishedAt: Number(body.publishedAt || 0),
      };
    } catch (err) {
      return json({ ok: false, error: 'bad_bundle' }, 400);
    }

    if (parsed.signingKey.length !== 32) return json({ ok: false, error: 'bad_signing_key' }, 400);
    if (parsed.dhIdentityKey.length !== 32) return json({ ok: false, error: 'bad_identity_key' }, 400);
    if (parsed.dhBinding.length !== 64) return json({ ok: false, error: 'bad_binding' }, 400);
    if (parsed.signedPreKey.length !== 32) return json({ ok: false, error: 'bad_signed_prekey' }, 400);

    // The mailbox must be derived from the identity key: nobody can claim somebody else's.
    if ((await mailboxIdFor(parsed.dhIdentityKey)) !== mailboxId) {
      return json({ ok: false, error: 'mailbox_mismatch' }, 400);
    }

    const bindingMessage = concatBytes([
      textEncoder.encode('PingIdentity/v1'),
      parsed.signingKey,
      parsed.dhIdentityKey,
    ]);
    if (!(await verifyEd25519(parsed.signingKey, bindingMessage, parsed.dhBinding))) {
      return json({ ok: false, error: 'bad_binding_signature' }, 400);
    }

    const preKeyMessage = concatBytes([
      textEncoder.encode('PingSignedPreKey/v1'),
      uint32Be(parsed.signedPreKeyId),
      parsed.signedPreKey,
    ]);
    if (!(await verifyEd25519(parsed.signingKey, preKeyMessage, parsed.signedPreKeySignature))) {
      return json({ ok: false, error: 'bad_prekey_signature' }, 400);
    }

    const skeleton = {
      signingKey: body.signingKey,
      dhIdentityKey: body.dhIdentityKey,
      dhBinding: body.dhBinding,
      signedPreKeyId: parsed.signedPreKeyId,
      signedPreKey: body.signedPreKey,
      signedPreKeySignature: body.signedPreKeySignature,
      publishedAt: parsed.publishedAt,
    };
    const preKeys = Array.isArray(body.oneTimePreKeys)
      ? body.oneTimePreKeys
          .filter((key) => key && typeof key.key === 'string' && Number.isInteger(Number(key.id)))
          .slice(0, LIMITS.maxPreKeysPerMailbox)
          .map((key) => ({ id: Number(key.id), key: String(key.key) }))
      : [];

    // Public keys are the only thing this object ever writes to storage.
    await this.state.storage.put({ skeleton, preKeys });
    this.skeleton = skeleton;
    this.preKeys = preKeys;
    return json({ ok: true, preKeys: preKeys.length });
  }

  async handleBundle(mailboxId) {
    if (!this.skeleton) return json({ ok: false, error: 'unknown_mailbox' }, 404);
    // Hand out exactly one one-time pre-key, and never the same one twice.
    const oneTime = this.preKeys.shift() || null;
    if (oneTime) await this.state.storage.put('preKeys', this.preKeys);
    return json({
      ok: true,
      bundle: {
        ...this.skeleton,
        oneTimePreKeyId: oneTime ? oneTime.id : null,
        oneTimePreKey: oneTime ? oneTime.key : null,
      },
    });
  }

  async handleSend(body) {
    let bytes;
    let target;
    try {
      bytes = base64urlToBytes(body.envelope);
      target = envelopeMailbox(bytes);
    } catch (err) {
      return json({ ok: false, error: err.message || 'bad_envelope' }, 400);
    }
    if (!this.skeleton) return json({ ok: false, error: 'unknown_mailbox' }, 404);
    if (this.envelopes.length >= LIMITS.maxEnvelopesPerMailbox) {
      return json({ ok: false, error: 'mailbox_full' }, 429);
    }
    if (this.queueBytes + bytes.length > LIMITS.maxQueueBytesPerMailbox) {
      return json({ ok: false, error: 'mailbox_full' }, 429);
    }

    const entry = { id: await envelopeId(bytes), data: bytes, receivedAt: Date.now() };
    this.envelopes.push(entry);
    this.queueBytes += bytes.length;

    const waiting = this.waiters.splice(0, this.waiters.length);
    for (const waiter of waiting) {
      clearTimeout(waiter.timer);
      waiter.resolve();
    }
    return json({ ok: true, id: entry.id, queued: true }, 202);
  }

  pollResponse() {
    return json({
      ok: true,
      envelopes: this.envelopes.slice(0, 50).map((entry) => ({ id: entry.id, data: bytesToBase64url(entry.data) })),
      nextNonce: this.mailboxId ? this.issueNonce(this.mailboxId) : null,
      cursor: Date.now(),
    });
  }

  async waitForEnvelope(waitMs) {
    // A fresh nonce is issued even when the wait times out, so the client never needs an extra
    // round trip just to keep its session alive.
    await new Promise((resolve) => {
      const waiter = { resolve, timer: null };
      waiter.timer = setTimeout(() => {
        const index = this.waiters.indexOf(waiter);
        if (index >= 0) this.waiters.splice(index, 1);
        resolve();
      }, waitMs);
      this.waiters.push(waiter);
    });
    this.sweep();
    return this.pollResponse();
  }
}

// ---------------------------------------------------------------------------- entry worker

function routeMailbox(path, method, body, url) {
  if (method === 'POST') return String(body.mailbox || '');
  return url.searchParams.get('mailbox') || '';
}

export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    const path = url.pathname;
    const method = request.method;

    if (method === 'OPTIONS') {
      return new Response(null, {
        status: 204,
        headers: {
          'access-control-allow-origin': '*',
          'access-control-allow-headers': 'content-type',
          'access-control-allow-methods': 'GET,POST,OPTIONS',
        },
      });
    }

    if (path === '/v1/health') {
      return json({
        ok: true,
        version: VERSION,
        time: Date.now(),
        runtime: 'cloudflare-workers',
        policy: {
          storesMessagesOnDisk: false,
          envelopeTtlMs: LIMITS.envelopeTtlMs,
          diskContents: 'public pre-key bundles only',
        },
      });
    }

    let bodyText = null;
    let body = null;
    if (method === 'POST') {
      bodyText = await request.text();
      if (bodyText.length > LIMITS.maxBodyBytes) return json({ ok: false, error: 'body_too_large' }, 413);
      try {
        body = bodyText ? JSON.parse(bodyText) : {};
      } catch (err) {
        return json({ ok: false, error: 'bad_json' }, 400);
      }
    }

    let mailboxId;
    try {
      if (path === '/v1/send' && method === 'POST') {
        mailboxId = envelopeMailbox(base64urlToBytes(body.envelope));
      } else {
        mailboxId = routeMailbox(path, method, body, url);
      }
    } catch (err) {
      return json({ ok: false, error: err.message || 'bad_request' }, 400);
    }

    if (!/^[A-Za-z0-9_-]{22}$/.test(mailboxId)) return json({ ok: false, error: 'bad_mailbox' }, 400);

    const id = env.MAILBOX.idFromName(mailboxId);
    const stub = env.MAILBOX.get(id);
    const forwardUrl = new URL('https://mailbox.internal' + path + url.search);
    return stub.fetch(
      new Request(forwardUrl, {
        method,
        headers: { 'content-type': 'application/json' },
        body: bodyText ?? undefined,
      })
    );
  },
};
