import http from 'node:http';
import * as store from './store.js';

const MAX_BODY = 25 * 1024 * 1024;
const LOCAL_ORIGIN = /^https?:\/\/(localhost|127\.0\.0\.1|\[::1\])(:\d+)?$/;

function readBody(req) {
  return new Promise((resolve, reject) => {
    const chunks = [];
    let size = 0;
    req.on('data', (c) => {
      size += c.length;
      if (size > MAX_BODY) {
        reject(Object.assign(new Error('payload too large'), { status: 413 }));
        req.destroy();
        return;
      }
      chunks.push(c);
    });
    req.on('end', () => resolve(Buffer.concat(chunks)));
    req.on('error', reject);
  });
}

async function readJson(req) {
  const body = await readBody(req);
  if (body.length === 0) return {};
  try {
    return JSON.parse(body.toString('utf8'));
  } catch {
    throw Object.assign(new Error('invalid JSON'), { status: 400 });
  }
}

function publicStatus(m) {
  return { id: m.id, status: m.status, message: m.message ?? null, counts: m.counts };
}

/**
 * Creates the ingest server without starting it.
 * `origins` lists extra allowed origins; localhost origins are always allowed.
 */
export function createServer({ root, origins = [], log = () => {} }) {
  const originOk = (o) => LOCAL_ORIGIN.test(o) || origins.includes(o) || origins.includes('*');

  const routes = [
    ['GET', /^\/health$/, async () => ({ ok: true })],
    ['POST', /^\/sessions$/, async (req) => {
      const m = store.createSession(root, await readJson(req));
      log(`session ${m.id} started (${m.url ?? 'unknown page'})`);
      return publicStatus(m);
    }],
    ['GET', /^\/sessions\/([^/]+)$/, async (_req, _url, id) => publicStatus(store.readManifest(root, id))],
    ['POST', /^\/sessions\/([^/]+)\/events$/, async (req, _url, id) => {
      store.readManifest(root, id);
      return { appended: store.appendEvents(root, id, await readJson(req)) };
    }],
    ['POST', /^\/sessions\/([^/]+)\/audio$/, async (req, url, id) => {
      store.readManifest(root, id);
      store.appendAudio(root, id, await readBody(req), url.searchParams.get('ext'));
      return { ok: true };
    }],
    ['POST', /^\/sessions\/([^/]+)\/frames$/, async (req, url, id) => {
      store.readManifest(root, id);
      const t = Number(url.searchParams.get('t'));
      if (!Number.isFinite(t)) throw Object.assign(new Error('missing t'), { status: 400 });
      return { file: store.writeFrame(root, id, t, await readBody(req)) };
    }],
    ['POST', /^\/sessions\/([^/]+)\/dom$/, async (req, _url, id) => {
      store.readManifest(root, id);
      store.writeDom(root, id, await readBody(req));
      return { ok: true };
    }],
    ['POST', /^\/sessions\/([^/]+)\/complete$/, async (req, _url, id) => {
      const m = store.completeSession(root, id, await readJson(req));
      log(`session ${m.id} complete: ${m.counts.events} events, ${m.counts.frames} frames`);
      return publicStatus(m);
    }],
  ];

  return http.createServer(async (req, res) => {
    const origin = req.headers.origin;
    if (origin && originOk(origin)) {
      res.setHeader('Access-Control-Allow-Origin', origin);
      res.setHeader('Vary', 'Origin');
      res.setHeader('Access-Control-Allow-Methods', 'GET, POST, OPTIONS');
      res.setHeader('Access-Control-Allow-Headers', 'Content-Type');
      res.setHeader('Access-Control-Max-Age', '600');
    }
    const send = (status, body) => {
      res.writeHead(status, { 'Content-Type': 'application/json' });
      res.end(JSON.stringify(body));
    };
    if (origin && !originOk(origin)) return send(403, { error: 'origin not allowed' });
    if (req.method === 'OPTIONS') {
      res.writeHead(204);
      return res.end();
    }
    const url = new URL(req.url, 'http://localhost');
    for (const [method, re, handler] of routes) {
      const match = req.method === method && re.exec(url.pathname);
      if (!match) continue;
      try {
        return send(200, await handler(req, url, ...match.slice(1)));
      } catch (err) {
        if (!err.status) log(`error on ${req.method} ${url.pathname}: ${err.stack}`);
        return send(err.status ?? 500, { error: err.message });
      }
    }
    send(404, { error: 'not found' });
  });
}

/** Starts the ingest server on the loopback interface and resolves once it is listening. */
export function serve({ root, port = 4777, origins, log = console.log }) {
  const server = createServer({ root, origins, log });
  return new Promise((resolve, reject) => {
    server.once('error', reject);
    server.listen(port, '127.0.0.1', () => {
      log(`polku server listening on http://127.0.0.1:${server.address().port}, writing to ${store.sessionsDir(root)}`);
      resolve(server);
    });
  });
}
