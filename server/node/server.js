#!/usr/bin/env node
/*
 * Ping relay server — zero dependencies, Node.js 18+.
 *
 *   node server.js --port 8080
 *
 * WHAT THIS SERVER IS
 *   A blind letterbox. It lets two phones that are never online at the same time exchange
 *   end-to-end encrypted envelopes. It cannot read anything: every message is encrypted and
 *   signed on the sender's device with keys the server never sees.
 *
 * WHAT THIS SERVER STORES
 *   In memory only, for at most ENVELOPE_TTL_MS:
 *     * end-to-end encrypted envelopes (opaque bytes)
 *     * a short-lived nonce per authentication attempt
 *   On disk (public key material only, never messages):
 *     * the published pre-key bundles: public keys + signatures
 *   Nothing else. No accounts, no phone numbers, no logs of who talks to whom, no IP storage.
 *
 * DELETING A MESSAGE
 *   Envelopes are removed from memory the moment the recipient acknowledges them, and swept
 *   when their TTL expires. Restarting the process also erases every envelope, by design.
 */

'use strict';

const http = require('http');
const crypto = require('crypto');
const fs = require('fs');
const path = require('path');

// ---------------------------------------------------------------------------- configuration

const args = process.argv.slice(2);
const argValue = (name, fallback) => {
  const i = args.indexOf(name);
  return i >= 0 && args[i + 1] ? args[i + 1] : fallback;
};

const CONFIG = {
  port: parseInt(argValue('--port', process.env.PORT || '8080'), 10),
  host: argValue('--host', process.env.HOST || '0.0.0.0'),
  // Public key material (pre-key bundles) is the only thing ever written to disk.
  dataFile: argValue('--data', process.env.PING_DATA || path.join(__dirname, 'ping-relay-keys.json')),
  verbose: args.includes('--verbose'),
  envelopeTtlMs: parseInt(argValue('--ttl-ms', '604800000'), 10),      // 7 days
  nonceTtlMs: 60_000,
  maxEnvelopeBytes: 512 * 1024,
  maxEnvelopesPerMailbox: 500,
  maxQueueBytesPerMailbox: 16 * 1024 * 1024,
  maxPreKeysPerMailbox: 200,
  maxBodyBytes: 640 * 1024,
  pollMaxWaitMs: 30_000,
  sendRatePerMinute: 240,
  registerRatePerMinute: 30,
};

const VERSION = 1;
const AUTH_PREFIX = 'PingRelayAuth/v1';
const MAILBOX_PREFIX = 'PingMailbox/v1';

// ---------------------------------------------------------------------------- state

/** mailboxId(base64url) -> { skeleton, preKeys: [], envelopes: [], queueBytes, pollers: [] } */
const mailboxes = new Map();
/** ip -> { count, resetAt } */
const rateCounters = new Map();
/** nonce(base64url) -> { mailbox, expiresAt, action } */
const nonces = new Map();

let stats = { received: 0, delivered: 0, dropped: 0, rejected: 0 };

function getMailbox(id) {
  let mailbox = mailboxes.get(id);
  if (!mailbox) {
    mailbox = { id, skeleton: null, preKeys: [], envelopes: [], queueBytes: 0, pollers: [] };
    mailboxes.set(id, mailbox);
  }
  return mailbox;
}

// ---------------------------------------------------------------------------- crypto helpers

const b64urlToBuffer = (text) => {
  if (typeof text !== 'string' || text.length > 800_000) throw new Error('bad base64url');
  if (!/^[A-Za-z0-9_-]*$/.test(text)) throw new Error('bad base64url');
  return Buffer.from(text, 'base64url');
};
const bufferToB64url = (buffer) => Buffer.from(buffer).toString('base64url');

/** Wraps a raw 32-byte Ed25519 key into the SPKI DER form Node/OpenSSL expects. */
function ed25519Spki(raw) {
  if (raw.length !== 32) throw new Error('ed25519 keys are 32 bytes');
  return Buffer.concat([Buffer.from('302a300506032b6570032100', 'hex'), raw]);
}

function verifyEd25519(rawPublicKey, message, signature) {
  if (rawPublicKey.length !== 32 || signature.length !== 64) return false;
  try {
    const key = crypto.createPublicKey({ key: ed25519Spki(rawPublicKey), format: 'der', type: 'spki' });
    return crypto.verify(null, message, key, signature);
  } catch (err) {
    if (CONFIG.verbose) console.error('[verify] ed25519 failed:', err.message);
    return false;
  }
}

const mailboxIdFor = (dhIdentityKey) =>
  crypto.createHash('sha256').update(MAILBOX_PREFIX, 'utf8').update(dhIdentityKey).digest().subarray(0, 16);

function verifyBundle(bundle) {
  const { signingKey, dhIdentityKey, dhBinding, signedPreKey, signedPreKeySignature } = bundle;
  if (!signingKey || signingKey.length !== 32) return 'bad_signing_key';
  if (!dhIdentityKey || dhIdentityKey.length !== 32) return 'bad_identity_key';
  if (!dhBinding || dhBinding.length !== 64) return 'bad_binding';
  if (!signedPreKey || signedPreKey.length !== 32) return 'bad_signed_prekey';
  if (!signedPreKeySignature || signedPreKeySignature.length !== 64) return 'bad_prekey_signature';

  // The mailbox must be derived from the identity key: nobody can register a mailbox they do
  // not own, and the relay cannot be tricked into hijacking somebody else's mailbox.
  if (bufferToB64url(mailboxIdFor(dhIdentityKey)) !== bundle.mailboxB64) return 'mailbox_mismatch';

  // Identity binding: proves the DH key belongs to the holder of the signing key.
  const bindingMessage = Buffer.concat([Buffer.from('PingIdentity/v1', 'utf8'), signingKey, dhIdentityKey]);
  if (!verifyEd25519(signingKey, bindingMessage, dhBinding)) return 'bad_binding_signature';

  // Signed pre-key: proves the pre-key was issued by the owner of the identity.
  const spkId = Buffer.alloc(4);
  spkId.writeUInt32BE(bundle.signedPreKeyId >>> 0, 0);
  const spkMessage = Buffer.concat([Buffer.from('PingSignedPreKey/v1', 'utf8'), spkId, signedPreKey]);
  if (!verifyEd25519(signingKey, spkMessage, signedPreKeySignature)) return 'bad_prekey_signature';
  return null;
}

// ---------------------------------------------------------------------------- persistence

/** Only public key material is written to disk. Envelopes live in RAM and nowhere else. */
function loadState() {
  try {
    const raw = fs.readFileSync(CONFIG.dataFile, 'utf8');
    const parsed = JSON.parse(raw);
    for (const entry of parsed.mailboxes || []) {
      const mailbox = getMailbox(entry.id);
      mailbox.skeleton = entry.skeleton;
      mailbox.preKeys = entry.preKeys || [];
    }
    if (CONFIG.verbose) console.log(`[state] loaded ${mailboxes.size} published pre-key bundles`);
  } catch (err) {
    if (err.code !== 'ENOENT') console.error('[state] could not read', CONFIG.dataFile, err.message);
  }
}

let saveTimer = null;
function scheduleSave() {
  if (saveTimer) return;
  saveTimer = setTimeout(() => {
    saveTimer = null;
    // Strips envelopes (never persisted) and empty mailboxes.
    const payload = {
      version: VERSION,
      savedAt: Date.now(),
      mailboxes: [...mailboxes.values()]
        .filter((m) => m.skeleton)
        .map((m) => ({ id: m.id, skeleton: m.skeleton, preKeys: m.preKeys })),
    };
    fs.writeFile(CONFIG.dataFile, JSON.stringify(payload), (err) => {
      if (err) console.error('[state] could not write', CONFIG.dataFile, err.message);
    });
  }, 500);
}

// ---------------------------------------------------------------------------- http helpers

function sendJson(res, status, body) {
  const text = JSON.stringify(body);
  res.writeHead(status, {
    'content-type': 'application/json; charset=utf-8',
    'content-length': Buffer.byteLength(text),
    'cache-control': 'no-store',
    'x-content-type-options': 'nosniff',
  });
  res.end(text);
}

function readBody(req) {
  return new Promise((resolve, reject) => {
    let size = 0;
    const chunks = [];
    req.on('data', (chunk) => {
      size += chunk.length;
      if (size > CONFIG.maxBodyBytes) {
        reject(Object.assign(new Error('body too large'), { status: 413, code: 'body_too_large' }));
        req.destroy();
        return;
      }
      chunks.push(chunk);
    });
    req.on('end', () => {
      try {
        resolve(chunks.length ? JSON.parse(Buffer.concat(chunks).toString('utf8')) : {});
      } catch (err) {
        reject(Object.assign(new Error('invalid json'), { status: 400, code: 'bad_json' }));
      }
    });
    req.on('error', reject);
  });
}

function rateLimit(ip, perMinute) {
  const now = Date.now();
  let entry = rateCounters.get(ip);
  if (!entry || entry.resetAt < now) {
    entry = { count: 0, resetAt: now + 60_000 };
    rateCounters.set(ip, entry);
  }
  entry.count += 1;
  return entry.count <= perMinute;
}

function issueNonce(mailboxId) {
  const nonce = bufferToB64url(crypto.randomBytes(32));
  nonces.set(nonce, { mailbox: mailboxId, expiresAt: Date.now() + CONFIG.nonceTtlMs });
  return nonce;
}

function consumeNonce(nonce, mailboxId) {
  const entry = nonces.get(nonce);
  if (!entry) return false;
  nonces.delete(nonce);                       // single use: a captured request can never be replayed
  if (entry.expiresAt < Date.now()) return false;
  return entry.mailbox === mailboxId;
}

function verifyAuthRequest(body, action) {
  const mailboxId = String(body.mailbox || '');
  const nonce = String(body.nonce || '');
  const signature = body.signature ? b64urlToBuffer(body.signature) : null;
  if (!mailboxId || !nonce || !signature) return { ok: false, code: 'missing_fields' };
  const mailbox = mailboxes.get(mailboxId);
  if (!mailbox || !mailbox.skeleton) return { ok: false, code: 'unknown_mailbox' };
  if (!consumeNonce(nonce, mailboxId)) return { ok: false, code: 'bad_nonce' };
  const signingKey = b64urlToBuffer(mailbox.skeleton.signingKey);
  const message = Buffer.concat([
    Buffer.from(AUTH_PREFIX, 'utf8'),
    Buffer.from(action, 'utf8'),
    b64urlToBuffer(mailboxId),
    b64urlToBuffer(nonce),
  ]);
  if (!verifyEd25519(signingKey, message, signature)) return { ok: false, code: 'bad_signature' };
  return { ok: true, mailbox };
}

// ---------------------------------------------------------------------------- envelope handling

/**
 * Reads just enough of the binary envelope to address it. The relay cannot parse anything else
 * and never looks at the payload.
 */
function parseEnvelopeAddress(buffer) {
  if (buffer.length < 18) throw Object.assign(new Error('envelope too short'), { code: 'bad_envelope' });
  if (buffer.length > CONFIG.maxEnvelopeBytes) throw Object.assign(new Error('envelope too large'), { code: 'envelope_too_large' });
  if (buffer[0] !== VERSION) throw Object.assign(new Error('bad version'), { code: 'bad_envelope' });
  const kind = buffer[1];
  if (kind !== 0 && kind !== 1) throw Object.assign(new Error('bad kind'), { code: 'bad_envelope' });
  return buffer.subarray(2, 18).toString('base64url');
}

function envelopeId(buffer) {
  return crypto.createHash('sha256').update(buffer).digest('base64url').slice(0, 22);
}

function queueEnvelope(mailboxId, buffer) {
  const mailbox = getMailbox(mailboxId);
  if (!mailbox.skeleton) return { ok: false, code: 'unknown_mailbox' };
  if (mailbox.envelopes.length >= CONFIG.maxEnvelopesPerMailbox) return { ok: false, code: 'mailbox_full' };
  if (mailbox.queueBytes + buffer.length > CONFIG.maxQueueBytesPerMailbox) return { ok: false, code: 'mailbox_full' };

  const entry = { id: envelopeId(buffer), data: buffer, receivedAt: Date.now() };
  mailbox.envelopes.push(entry);
  mailbox.queueBytes += buffer.length;
  stats.received += 1;

  // Wake up anybody polling this mailbox.
  const waiting = mailbox.pollers.splice(0, mailbox.pollers.length);
  for (const waiter of waiting) {
    clearTimeout(waiter.timer);
    waiter.respond();
  }
  return { ok: true, id: entry.id };
}

function envelopesFor(mailbox) {
  // The id travels with the envelope so the receiver can acknowledge exactly what it processed.
  return mailbox.envelopes.slice(0, 50).map((entry) => ({ id: entry.id, data: bufferToB64url(entry.data) }));
}

function sweep() {
  const now = Date.now();
  for (const mailbox of mailboxes.values()) {
    const keep = [];
    let bytes = 0;
    for (const entry of mailbox.envelopes) {
      if (now - entry.receivedAt > CONFIG.envelopeTtlMs) {
        stats.dropped += 1;
        continue;
      }
      keep.push(entry);
      bytes += entry.data.length;
    }
    mailbox.envelopes = keep;
    mailbox.queueBytes = bytes;
    if (mailbox.skeleton) continue;
    if (mailbox.preKeys.length === 0 && mailbox.envelopes.length === 0) mailboxes.delete(mailbox.id);
  }
  for (const [nonce, entry] of nonces) if (entry.expiresAt < now) nonces.delete(nonce);
  for (const [ip, entry] of rateCounters) if (entry.resetAt < now && Math.random() < 0.05) rateCounters.delete(ip);
}

// ---------------------------------------------------------------------------- routes

async function handleRequest(req, res) {
  const ip = req.socket.remoteAddress || 'unknown';
  const url = new URL(req.url, 'http://localhost');
  const route = `${req.method} ${url.pathname}`;

  if (req.method === 'OPTIONS') {
    res.writeHead(204, { 'access-control-allow-origin': '*', 'access-control-allow-headers': 'content-type', 'access-control-allow-methods': 'GET,POST,OPTIONS' });
    return res.end();
  }

  if (route === 'GET /v1/health') {
    return sendJson(res, 200, {
      ok: true,
      version: VERSION,
      time: Date.now(),
      mailboxes: mailboxes.size,
      envelopesInMemory: [...mailboxes.values()].reduce((n, m) => n + m.envelopes.length, 0),
      stats,
      policy: {
        storesMessagesOnDisk: false,
        envelopeTtlMs: CONFIG.envelopeTtlMs,
        diskContents: 'public pre-key bundles only',
      },
    });
  }

  if (route === 'POST /v1/register') {
    if (!rateLimit('reg:' + ip, CONFIG.registerRatePerMinute)) return sendJson(res, 429, { ok: false, error: 'rate_limited' });
    const body = await readBody(req);
    const mailboxId = String(body.mailbox || '');
    let bundle;
    try {
      bundle = {
        mailboxB64: mailboxId,
        signingKey: b64urlToBuffer(body.signingKey),
        dhIdentityKey: b64urlToBuffer(body.dhIdentityKey),
        dhBinding: b64urlToBuffer(body.dhBinding),
        signedPreKeyId: Number(body.signedPreKeyId || 0) | 0,
        signedPreKey: b64urlToBuffer(body.signedPreKey),
        signedPreKeySignature: b64urlToBuffer(body.signedPreKeySignature),
        publishedAt: Number(body.publishedAt || 0),
        oneTimePreKeyId: null,
        oneTimePreKey: null,
      };
    } catch (err) {
      return sendJson(res, 400, { ok: false, error: 'bad_bundle' });
    }
    const problem = verifyBundle(bundle);
    if (problem) {
      stats.rejected += 1;
      return sendJson(res, 400, { ok: false, error: problem });
    }

    const mailbox = getMailbox(mailboxId);
    mailbox.skeleton = {
      signingKey: body.signingKey,
      dhIdentityKey: body.dhIdentityKey,
      dhBinding: body.dhBinding,
      signedPreKeyId: bundle.signedPreKeyId,
      signedPreKey: body.signedPreKey,
      signedPreKeySignature: body.signedPreKeySignature,
      publishedAt: bundle.publishedAt,
    };
    mailbox.preKeys = Array.isArray(body.oneTimePreKeys)
      ? body.oneTimePreKeys
          .filter((k) => k && typeof k.key === 'string' && Number.isInteger(Number(k.id)))
          .slice(0, CONFIG.maxPreKeysPerMailbox)
          .map((k) => ({ id: Number(k.id), key: String(k.key) }))
      : [];
    scheduleSave();
    return sendJson(res, 200, { ok: true, preKeys: mailbox.preKeys.length });
  }

  if (route === 'GET /v1/bundle') {
    const mailboxId = url.searchParams.get('mailbox') || '';
    const mailbox = mailboxes.get(mailboxId);
    if (!mailbox || !mailbox.skeleton) return sendJson(res, 404, { ok: false, error: 'unknown_mailbox' });

    // Hand out one one-time pre-key, and only once.
    const oneTime = mailbox.preKeys.shift() || null;
    if (oneTime) scheduleSave();

    const s = mailbox.skeleton;
    return sendJson(res, 200, {
      ok: true,
      bundle: {
        signingKey: s.signingKey,
        dhIdentityKey: s.dhIdentityKey,
        dhBinding: s.dhBinding,
        signedPreKeyId: s.signedPreKeyId,
        signedPreKey: s.signedPreKey,
        signedPreKeySignature: s.signedPreKeySignature,
        publishedAt: s.publishedAt,
        oneTimePreKeyId: oneTime ? oneTime.id : null,
        oneTimePreKey: oneTime ? oneTime.key : null,
      },
    });
  }

  if (route === 'POST /v1/send') {
    if (!rateLimit('send:' + ip, CONFIG.sendRatePerMinute)) return sendJson(res, 429, { ok: false, error: 'rate_limited' });
    const body = await readBody(req);
    let mailboxId;
    let buffer;
    try {
      buffer = b64urlToBuffer(body.envelope);
      mailboxId = parseEnvelopeAddress(buffer);
    } catch (err) {
      stats.rejected += 1;
      return sendJson(res, 400, { ok: false, error: err.code || 'bad_envelope' });
    }
    const result = queueEnvelope(mailboxId, buffer);
    if (!result.ok) return sendJson(res, result.code === 'unknown_mailbox' ? 404 : 429, { ok: false, error: result.code });
    return sendJson(res, 202, { ok: true, id: result.id, queued: true });
  }

  if (route === 'GET /v1/challenge') {
    const mailboxId = url.searchParams.get('mailbox') || '';
    const mailbox = mailboxes.get(mailboxId);
    if (!mailbox || !mailbox.skeleton) return sendJson(res, 404, { ok: false, error: 'unknown_mailbox' });
    if (!rateLimit('chal:' + ip, 240)) return sendJson(res, 429, { ok: false, error: 'rate_limited' });
    return sendJson(res, 200, { ok: true, nonce: issueNonce(mailboxId) });
  }

  if (route === 'POST /v1/poll') {
    const body = await readBody(req);
    const auth = verifyAuthRequest(body, 'poll');
    if (!auth.ok) return sendJson(res, auth.code === 'unknown_mailbox' ? 404 : 401, { ok: false, error: auth.code });
    const mailbox = auth.mailbox;
    const waitMs = Math.min(Number(body.waitMs || 0) || 0, CONFIG.pollMaxWaitMs);

    // A fresh nonce rides along so the client can acknowledge without another round trip.
    const respond = () => sendJson(res, 200, { ok: true, envelopes: envelopesFor(mailbox), nextNonce: issueNonce(mailbox.id), cursor: Date.now() });

    if (mailbox.envelopes.length > 0 || waitMs <= 0) {
      stats.delivered += Math.min(mailbox.envelopes.length, 50);
      return respond();
    }

    // Long poll: hold the connection until something arrives or the wait expires.
    const waiter = { respond: () => {}, timer: null };
    waiter.respond = () => {
      const index = mailbox.pollers.indexOf(waiter);
      if (index >= 0) mailbox.pollers.splice(index, 1);
      if (res.writableEnded) return;
      stats.delivered += Math.min(mailbox.envelopes.length, 50);
      respond();
    };
    waiter.timer = setTimeout(waiter.respond, waitMs);
    mailbox.pollers.push(waiter);
    req.on('close', () => {
      clearTimeout(waiter.timer);
      const index = mailbox.pollers.indexOf(waiter);
      if (index >= 0) mailbox.pollers.splice(index, 1);
    });
    return undefined;
  }

  if (route === 'POST /v1/ack') {
    const body = await readBody(req);
    const auth = verifyAuthRequest(body, 'ack');
    if (!auth.ok) return sendJson(res, auth.code === 'unknown_mailbox' ? 404 : 401, { ok: false, error: auth.code });
    const ids = new Set(Array.isArray(body.ids) ? body.ids.map(String) : []);
    const mailbox = auth.mailbox;
    const before = mailbox.envelopes.length;
    mailbox.envelopes = mailbox.envelopes.filter((entry) => !ids.has(entry.id));
    mailbox.queueBytes = mailbox.envelopes.reduce((n, entry) => n + entry.data.length, 0);
    return sendJson(res, 200, { ok: true, removed: before - mailbox.envelopes.length });
  }

  if (route === 'POST /v1/prekeys') {
    const body = await readBody(req);
    const auth = verifyAuthRequest(body, 'keys');
    if (!auth.ok) return sendJson(res, auth.code === 'unknown_mailbox' ? 404 : 401, { ok: false, error: auth.code });
    const mailbox = auth.mailbox;
    const incoming = Array.isArray(body.oneTimePreKeys) ? body.oneTimePreKeys : [];
    for (const key of incoming) {
      if (!key || typeof key.key !== 'string' || key.key.length > 128) continue;
      if (!Number.isInteger(Number(key.id))) continue;
      if (mailbox.preKeys.length >= CONFIG.maxPreKeysPerMailbox) break;
      if (mailbox.preKeys.some((existing) => existing.id === Number(key.id))) continue;
      mailbox.preKeys.push({ id: Number(key.id), key: key.key });
    }
    scheduleSave();
    return sendJson(res, 200, { ok: true, preKeys: mailbox.preKeys.length, nextNonce: issueNonce(mailbox.id) });
  }

  return sendJson(res, 404, { ok: false, error: 'not_found' });
}

const server = http.createServer((req, res) => {
  handleRequest(req, res).catch((err) => {
    if (CONFIG.verbose) console.error('[error]', err);
    if (!res.writableEnded) sendJson(res, err.status || 400, { ok: false, error: err.code || 'bad_request' });
  });
});

server.headersTimeout = 60_000;
server.requestTimeout = 60_000;

loadState();
setInterval(sweep, 60_000).unref?.();

server.listen(CONFIG.port, CONFIG.host, () => {
  console.log(`Ping relay listening on http://${CONFIG.host}:${CONFIG.port}`);
  console.log(`Envelopes: memory only, TTL ${Math.round(CONFIG.envelopeTtlMs / 3600000)}h`);
  console.log(`Pre-key bundles (public keys): ${CONFIG.dataFile}`);
  if (CONFIG.verbose) console.log('verbose mode enabled — do not use it with real conversations');
});

const shutdown = () => {
  console.log('\nShutting down: every envelope in memory is erased now.');
  for (const mailbox of mailboxes.values()) {
    for (const waiter of mailbox.pollers.splice(0)) clearTimeout(waiter.timer);
    mailbox.envelopes = [];
  }
  server.close(() => process.exit(0));
  setTimeout(() => process.exit(0), 1000).unref();
};
process.on('SIGINT', shutdown);
process.on('SIGTERM', shutdown);
