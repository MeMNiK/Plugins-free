// FoolioBridge - Development by DeJIoVoy
'use strict';

/**
 * foolio-bridge — site side of the FoolioBridge protocol v1.
 * Zero dependencies. Works with plain `http`, Express, Fastify (raw body), Koa, etc.
 *
 * Protocol details: see PROTOCOL.md.
 */

const crypto = require('crypto');
const fs = require('fs');
const path = require('path');

const PROTOCOL_VERSION = 1;
const MAX_BODY = 1024 * 1024;

// ------------------------------------------------------------------ signing

function sign(secret, kind, ts, nonce, body) {
  return crypto
    .createHmac('sha256', secret)
    .update(`foolio-v1:${kind}:${ts}:${nonce}:${body}`, 'utf8')
    .digest('hex');
}

function safeEqual(a, b) {
  const x = Buffer.from(String(a || ''), 'utf8');
  const y = Buffer.from(String(b || ''), 'utf8');
  return x.length === y.length && crypto.timingSafeEqual(x, y);
}

// ------------------------------------------------------------------ order validation

const ORDER_FIELDS = ['id', 'player', 'commands'];

function normalizeOrder(o) {
  for (const f of ORDER_FIELDS) {
    if (o == null || o[f] == null || o[f] === '') throw new Error(`order is missing "${f}"`);
  }
  if (!Array.isArray(o.commands) || o.commands.length === 0) {
    throw new Error(`order ${o.id}: "commands" must be a non-empty array`);
  }
  const out = {
    id: String(o.id),
    player: String(o.player),
    commands: o.commands.map(String),
  };
  if (out.id.length > 64) throw new Error(`order ${out.id}: id longer than 64 chars`);
  if (o.uuid) out.uuid = String(o.uuid);
  if (o.online_only === false) out.online_only = false;
  if (o.title) out.title = String(o.title);
  if (o.message) out.message = String(o.message);
  if (o.vars && typeof o.vars === 'object') {
    out.vars = {};
    for (const [k, v] of Object.entries(o.vars)) out.vars[k] = String(v);
  }
  return out;
}

// ------------------------------------------------------------------ raw body

function readRawBody(req) {
  if (Buffer.isBuffer(req.rawBody)) return Promise.resolve(req.rawBody.toString('utf8'));
  if (typeof req.rawBody === 'string') return Promise.resolve(req.rawBody);
  if (Buffer.isBuffer(req.body)) return Promise.resolve(req.body.toString('utf8'));
  if (typeof req.body === 'string') return Promise.resolve(req.body);
  if (req.body && typeof req.body === 'object' && (req.readableEnded || req.complete)) {
    return Promise.reject(Object.assign(
      new Error('Request body was already parsed as JSON, signature cannot be checked. ' +
        'Mount the FoolioBridge route BEFORE express.json(), or use ' +
        'express.json({ verify: (req, res, buf) => { req.rawBody = buf; } })'),
      { status: 500 }));
  }
  return new Promise((resolve, reject) => {
    const chunks = [];
    let size = 0;
    req.on('data', (c) => {
      size += c.length;
      if (size > MAX_BODY) {
        reject(Object.assign(new Error('body too large'), { status: 413 }));
        req.destroy();
        return;
      }
      chunks.push(c);
    });
    req.on('end', () => resolve(Buffer.concat(chunks).toString('utf8')));
    req.on('error', reject);
  });
}

function header(req, name) {
  const h = req.headers || {};
  const v = h[name.toLowerCase()];
  return Array.isArray(v) ? v[0] : v;
}

// ------------------------------------------------------------------ bridge

/**
 * @param {object} opts
 * @param {string} opts.secret  shared secret, same as in the plugin's config.yml (16+ chars)
 * @param {(serverId: string) => Promise<object[]>|object[]} opts.getPendingOrders
 *        orders that should be delivered on this server and haven't been confirmed yet
 * @param {(serverId: string, results: object[]) => Promise<string[]|void>|string[]|void} opts.onResults
 *        delivery results from the server; return the ids you saved (or nothing = all)
 * @param {string[]} [opts.allowedServers]  restrict which server ids may connect
 * @param {number} [opts.maxSkewSeconds=300]
 * @param {(msg: string, err?: Error) => void} [opts.log]
 */
function createFoolioBridge(opts) {
  if (!opts || typeof opts.secret !== 'string' || opts.secret.length < 16) {
    throw new Error('foolio-bridge: secret must be a string of at least 16 characters');
  }
  if (typeof opts.getPendingOrders !== 'function' || typeof opts.onResults !== 'function') {
    throw new Error('foolio-bridge: getPendingOrders and onResults are required');
  }
  const secret = opts.secret;
  const maxSkew = opts.maxSkewSeconds || 300;
  const allowed = opts.allowedServers ? new Set(opts.allowedServers) : null;
  const log = opts.log || ((m, e) => console.warn('[foolio-bridge] ' + m, e ? e.message : ''));
  const seenNonces = new Map(); // nonce -> expiresAt

  function rememberNonce(nonce, now) {
    for (const [n, exp] of seenNonces) {
      if (exp > now) break;
      seenNonces.delete(n);
    }
    if (seenNonces.has(nonce)) return false;
    seenNonces.set(nonce, now + maxSkew * 2);
    return true;
  }

  function send(res, status, obj, nonce) {
    const body = JSON.stringify(obj);
    const headers = { 'Content-Type': 'application/json; charset=utf-8' };
    if (nonce) {
      const ts = Math.floor(Date.now() / 1000);
      headers['X-Foolio-Timestamp'] = String(ts);
      headers['X-Foolio-Signature'] = sign(secret, 'res', ts, nonce, body);
    }
    if (typeof res.writeHead === 'function' && !res.status) {
      res.writeHead(status, headers);
      res.end(body);
    } else {
      // Express / Koa-ish response
      res.status(status);
      for (const [k, v] of Object.entries(headers)) res.setHeader(k, v);
      res.end(body);
    }
  }

  /** Processes an already-read request. Useful for frameworks that hand you the raw body. */
  async function process(rawBody, headers) {
    const get = (n) => header({ headers }, n);
    const tsStr = get('x-foolio-timestamp');
    const nonce = get('x-foolio-nonce');
    const sig = get('x-foolio-signature');
    if (!tsStr || !nonce || !sig) return { status: 401, body: { ok: false, error: 'unsigned request' } };
    const ts = Number(tsStr);
    const now = Math.floor(Date.now() / 1000);
    if (!Number.isFinite(ts) || Math.abs(now - ts) > maxSkew) {
      return { status: 401, body: { ok: false, error: 'timestamp out of range, check server clocks' } };
    }
    if (!/^[0-9a-f]{16,64}$/.test(nonce)) return { status: 401, body: { ok: false, error: 'bad nonce' } };
    if (!safeEqual(sign(secret, 'req', ts, nonce, rawBody), String(sig).toLowerCase())) {
      return { status: 401, body: { ok: false, error: 'bad signature' } };
    }
    if (!rememberNonce(nonce, now)) return { status: 401, body: { ok: false, error: 'replayed request' } };

    let msg;
    try {
      msg = JSON.parse(rawBody);
    } catch (e) {
      return { status: 400, body: { ok: false, error: 'invalid JSON' }, nonce };
    }
    if (msg.v !== PROTOCOL_VERSION) {
      return { status: 400, body: { ok: false, error: `unsupported protocol version ${msg.v}` }, nonce };
    }
    const server = String(msg.server || '');
    if (allowed && !allowed.has(server)) {
      return { status: 403, body: { ok: false, error: `server "${server}" is not allowed` }, nonce };
    }

    switch (msg.action) {
      case 'ping':
        return { status: 200, body: { ok: true, pong: true, server }, nonce };

      case 'pending': {
        const raw = (await opts.getPendingOrders(server)) || [];
        const orders = [];
        for (const o of raw) {
          try {
            orders.push(normalizeOrder(o));
          } catch (e) {
            log('skipping bad order', e);
          }
        }
        return { status: 200, body: { ok: true, orders }, nonce };
      }

      case 'ack': {
        const results = Array.isArray(msg.results) ? msg.results : [];
        const clean = results
          .filter((r) => r && r.id != null)
          .map((r) => ({
            id: String(r.id),
            player: r.player != null ? String(r.player) : null,
            status: r.status === 'failed' ? 'failed' : 'delivered',
            delivered_at: Number(r.delivered_at) || Date.now(),
            errors: Array.isArray(r.errors) ? r.errors.map(String) : [],
          }));
        const saved = await opts.onResults(server, clean);
        const accepted = Array.isArray(saved) ? saved.map(String) : clean.map((r) => r.id);
        return { status: 200, body: { ok: true, accepted }, nonce };
      }

      default:
        return { status: 400, body: { ok: false, error: `unknown action "${msg.action}"` }, nonce };
    }
  }

  /** (req, res) handler for node:http and Express. */
  async function handler(req, res) {
    if (req.method && req.method !== 'POST') return send(res, 405, { ok: false, error: 'POST only' });
    let raw;
    try {
      raw = await readRawBody(req);
    } catch (e) {
      log('cannot read body', e);
      return send(res, e.status || 400, { ok: false, error: e.message });
    }
    let out;
    try {
      out = await process(raw, req.headers);
    } catch (e) {
      log('handler error', e);
      return send(res, 500, { ok: false, error: 'internal error' });
    }
    return send(res, out.status, out.body, out.nonce);
  }

  return { handler, process, sign: (kind, ts, nonce, body) => sign(secret, kind, ts, nonce, body) };
}

// ------------------------------------------------------------------ simple file queue

/**
 * Optional ready-made storage for small sites without a database.
 * Your payment webhook calls queue.add(...) once a payment succeeds; the bridge does the rest.
 */
class FileOrderQueue {
  constructor(file) {
    this.file = path.resolve(file);
    this.data = { seq: 0, orders: [] };
    if (fs.existsSync(this.file)) this.data = JSON.parse(fs.readFileSync(this.file, 'utf8'));
  }

  _save() {
    const tmp = this.file + '.tmp';
    fs.mkdirSync(path.dirname(this.file), { recursive: true });
    fs.writeFileSync(tmp, JSON.stringify(this.data, null, 2));
    fs.renameSync(tmp, this.file);
  }

  /**
   * @param {object} order { player, commands, title?, message?, server?, online_only?, vars?, id? }
   *        server: deliver only on this server id (omit = any server)
   * @returns {object} the stored order with its id
   */
  add(order) {
    const id = order.id != null ? String(order.id) : String(++this.data.seq);
    if (this.data.orders.some((o) => o.id === id)) throw new Error(`order ${id} already exists`);
    const stored = normalizeOrder({ ...order, id });
    stored.server = order.server || null;
    stored.status = 'pending';
    stored.created_at = Date.now();
    this.data.orders.push(stored);
    this._save();
    return stored;
  }

  pending(serverId) {
    return this.data.orders.filter(
      (o) => o.status === 'pending' && (!o.server || o.server === serverId));
  }

  applyResults(serverId, results) {
    const accepted = [];
    for (const r of results) {
      const o = this.data.orders.find((x) => x.id === r.id);
      if (!o) continue;
      o.status = r.status;
      o.delivered_at = r.delivered_at;
      o.delivered_on = serverId;
      o.errors = r.errors;
      accepted.push(r.id);
    }
    if (accepted.length) this._save();
    return accepted;
  }

  list() {
    return this.data.orders.slice();
  }
}

module.exports = { createFoolioBridge, FileOrderQueue, sign, PROTOCOL_VERSION };
