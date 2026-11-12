import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';

const ID_RE = /^[0-9]{8}-[0-9]{6}-[a-f0-9]{6}$/;

export const STATUS = {
  recording: 'recording',
  complete: 'complete',
  claimed: 'claimed',
  done: 'done',
};

/** Resolves the directory that holds one subdirectory per session. */
export function sessionsDir(root) {
  return path.join(root, 'sessions');
}

function newId(now) {
  const p = (n) => String(n).padStart(2, '0');
  const date = `${now.getFullYear()}${p(now.getMonth() + 1)}${p(now.getDate())}`;
  const time = `${p(now.getHours())}${p(now.getMinutes())}${p(now.getSeconds())}`;
  return `${date}-${time}-${crypto.randomBytes(3).toString('hex')}`;
}

/** Returns the session directory, or throws when the id could escape the sessions directory. */
export function sessionDir(root, id) {
  if (!ID_RE.test(id)) throw Object.assign(new Error('invalid session id'), { status: 400 });
  return path.join(sessionsDir(root), id);
}

export function readManifest(root, id) {
  const file = path.join(sessionDir(root, id), 'manifest.json');
  if (!fs.existsSync(file)) throw Object.assign(new Error('unknown session'), { status: 404 });
  return JSON.parse(fs.readFileSync(file, 'utf8'));
}

/** Replaces the manifest through a rename so a concurrent reader sees a whole file. */
export function writeManifest(root, manifest) {
  const dir = sessionDir(root, manifest.id);
  const tmp = path.join(dir, `.manifest.${process.pid}.tmp`);
  fs.writeFileSync(tmp, JSON.stringify(manifest, null, 2) + '\n');
  fs.renameSync(tmp, path.join(dir, 'manifest.json'));
  return manifest;
}

export function updateManifest(root, id, fn) {
  const manifest = readManifest(root, id);
  return writeManifest(root, fn(manifest) ?? manifest);
}

export function createSession(root, meta) {
  const now = new Date();
  const id = newId(now);
  fs.mkdirSync(path.join(sessionDir(root, id), 'frames'), { recursive: true });
  return writeManifest(root, {
    id,
    status: STATUS.recording,
    createdAt: now.toISOString(),
    // Epoch ms on the browser clock at which event offset `t` is zero
    startedAtMs: meta.startedAtMs ?? now.getTime(),
    url: meta.url ?? null,
    title: meta.title ?? null,
    viewport: meta.viewport ?? null,
    userAgent: meta.userAgent ?? null,
    lang: meta.lang ?? null,
    capture: meta.capture ?? {},
    counts: { events: 0, frames: 0, audioBytes: 0 },
  });
}

export function appendEvents(root, id, events) {
  if (!Array.isArray(events) || events.length === 0) return 0;
  const lines = events.map((e) => JSON.stringify(e)).join('\n') + '\n';
  fs.appendFileSync(path.join(sessionDir(root, id), 'events.jsonl'), lines);
  updateManifest(root, id, (m) => {
    m.counts.events += events.length;
  });
  return events.length;
}

export function appendAudio(root, id, chunk, ext) {
  const safeExt = /^[a-z0-9]{2,5}$/.test(ext ?? '') ? ext : 'webm';
  fs.appendFileSync(path.join(sessionDir(root, id), `audio.${safeExt}`), chunk);
  updateManifest(root, id, (m) => {
    m.counts.audioBytes += chunk.length;
    m.audioFile = `audio.${safeExt}`;
  });
}

/** Names a frame by its zero-padded offset so a directory listing is in time order. */
export function frameName(t) {
  return `${String(Math.max(0, Math.round(t))).padStart(7, '0')}.jpg`;
}

export function writeFrame(root, id, t, jpeg) {
  const name = frameName(t);
  fs.writeFileSync(path.join(sessionDir(root, id), 'frames', name), jpeg);
  updateManifest(root, id, (m) => {
    m.counts.frames += 1;
  });
  return name;
}

export function writeDom(root, id, html) {
  fs.writeFileSync(path.join(sessionDir(root, id), 'dom.html'), html);
}

export function completeSession(root, id, { durationMs }) {
  const dir = sessionDir(root, id);
  const events = readEvents(root, id);
  const note = events.find((e) => e.type === 'note')?.text;
  const transcript = events
    .filter((e) => e.type === 'speech')
    .map((e) => `[${fmtT(e.t)}] ${e.text}`)
    .join('\n');
  if (transcript) fs.writeFileSync(path.join(dir, 'transcript.txt'), transcript + '\n');
  if (note) fs.writeFileSync(path.join(dir, 'note.txt'), note + '\n');
  return updateManifest(root, id, (m) => {
    m.status = STATUS.complete;
    m.completedAt = new Date().toISOString();
    m.durationMs = durationMs ?? null;
    m.hasTranscript = Boolean(transcript);
    m.hasNote = Boolean(note);
  });
}

export function readEvents(root, id) {
  const file = path.join(sessionDir(root, id), 'events.jsonl');
  if (!fs.existsSync(file)) return [];
  return fs
    .readFileSync(file, 'utf8')
    .split('\n')
    .filter(Boolean)
    .map((line) => JSON.parse(line))
    .sort((a, b) => a.t - b.t);
}

export function listFrames(root, id) {
  const dir = path.join(sessionDir(root, id), 'frames');
  if (!fs.existsSync(dir)) return [];
  return fs
    .readdirSync(dir)
    .filter((f) => f.endsWith('.jpg'))
    .sort()
    .map((f) => ({ t: Number(f.slice(0, -4)), file: path.join(dir, f) }));
}

export function listSessions(root) {
  const dir = sessionsDir(root);
  if (!fs.existsSync(dir)) return [];
  return fs
    .readdirSync(dir)
    .filter((id) => ID_RE.test(id))
    .sort()
    .flatMap((id) => {
      try {
        return [readManifest(root, id)];
      } catch {
        // A session directory whose manifest is still being created
        return [];
      }
    });
}

/** Formats a millisecond offset as `m:ss.s`. */
export function fmtT(ms) {
  const s = Math.max(0, ms) / 1000;
  const m = Math.floor(s / 60);
  return `${m}:${(s - m * 60).toFixed(1).padStart(4, '0')}`;
}
