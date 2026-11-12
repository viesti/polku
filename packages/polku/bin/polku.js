#!/usr/bin/env node
import fs from 'node:fs';
import path from 'node:path';
import { spawn } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { parseArgs } from 'node:util';
import { serve } from '../server/index.js';
import * as store from '../server/store.js';
import { buildSummary, buildTask } from '../server/summary.js';

const USAGE = `polku <command> [options]

  serve                 Start the local ingest server that the browser client posts to
      --port <n>        Port to listen on (default 4777)
      --origin <url>    Extra allowed browser origin; repeatable. Localhost is always allowed
  wait                  Block until a recorded session is ready, claim it, print its task brief
      --timeout <s>     Exit with code 2 if nothing arrives within this many seconds
  watch -- <cmd...>     For each new session, run <cmd> with the task brief on stdin
                        e.g. polku watch -- claude -p --permission-mode acceptEdits
  done [id] -m <text>   Mark a session finished and show <text> in the user's browser
  summary [id]          Print the brief for a session (default: the latest)
  events [id]           Print the raw events of a session as JSON lines
      --type <t>        Only events of this type; repeatable
  list                  List sessions and their status
  init                  Install the Claude Code skill and gitignore entry in this project

Common options:
      --dir <path>      Workspace directory for session artifacts (default .polku)
`;

const { values: opts, positionals } = parseArgs({
  allowPositionals: true,
  options: {
    dir: { type: 'string', default: '.polku' },
    port: { type: 'string', default: '4777' },
    origin: { type: 'string', multiple: true, default: [] },
    timeout: { type: 'string' },
    message: { type: 'string', short: 'm' },
    type: { type: 'string', multiple: true, default: [] },
    help: { type: 'boolean', short: 'h' },
  },
});

const [command, ...args] = positionals;
const root = path.resolve(opts.dir);
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

function fail(msg) {
  console.error(`polku: ${msg}`);
  process.exit(1);
}

function latest(statuses) {
  const found = store.listSessions(root).filter((m) => !statuses || statuses.includes(m.status));
  return found.at(-1)?.id;
}

/** Claims the oldest complete session and returns its id, or returns null when none is ready. */
function claimNext() {
  const next = store.listSessions(root).find((m) => m.status === store.STATUS.complete);
  if (!next) return null;
  store.updateManifest(root, next.id, (m) => {
    m.status = store.STATUS.claimed;
    m.claimedAt = new Date().toISOString();
  });
  return next.id;
}

async function waitForSession(timeoutS) {
  const deadline = timeoutS ? Date.now() + timeoutS * 1000 : Infinity;
  for (;;) {
    const id = claimNext();
    if (id) return id;
    if (Date.now() > deadline) return null;
    await sleep(1000);
  }
}

function writeTask(id) {
  const task = buildTask(root, id);
  fs.writeFileSync(path.join(root, 'CURRENT_TASK.md'), task);
  return task;
}

function markDone(id, message) {
  store.updateManifest(root, id, (m) => {
    m.status = store.STATUS.done;
    m.doneAt = new Date().toISOString();
    m.message = message ?? null;
  });
}

const commands = {
  async serve() {
    fs.mkdirSync(store.sessionsDir(root), { recursive: true });
    await serve({ root, port: Number(opts.port), origins: opts.origin });
  },

  async wait() {
    fs.mkdirSync(store.sessionsDir(root), { recursive: true });
    const id = await waitForSession(opts.timeout ? Number(opts.timeout) : null);
    if (!id) process.exit(2);
    process.stdout.write(writeTask(id));
  },

  async watch() {
    if (args.length === 0) fail('watch needs a command, e.g. polku watch -- claude -p');
    fs.mkdirSync(store.sessionsDir(root), { recursive: true });
    console.error(`polku: watching ${store.sessionsDir(root)}`);
    for (;;) {
      const id = await waitForSession(null);
      console.error(`polku: session ${id} → ${args.join(' ')}`);
      const code = await new Promise((resolve) => {
        const child = spawn(args[0], args.slice(1), { stdio: ['pipe', 'inherit', 'inherit'] });
        child.on('error', (err) => {
          console.error(`polku: could not run ${args[0]}: ${err.message}`);
          resolve(1);
        });
        child.on('exit', resolve);
        child.stdin.on('error', () => {});
        child.stdin.end(writeTask(id));
      });
      if (store.readManifest(root, id).status !== store.STATUS.done) {
        markDone(id, code === 0 ? 'The change is in.' : 'The agent stopped before finishing this request.');
      }
    }
  },

  done() {
    const id = args[0] ?? latest([store.STATUS.claimed]);
    if (!id) fail('no claimed session to finish');
    markDone(id, opts.message);
    console.log(`session ${id} marked done`);
  },

  summary() {
    const id = args[0] ?? latest();
    if (!id) fail('no sessions recorded yet');
    process.stdout.write(buildSummary(root, id));
  },

  events() {
    const id = args[0] ?? latest();
    if (!id) fail('no sessions recorded yet');
    for (const e of store.readEvents(root, id)) {
      if (opts.type.length === 0 || opts.type.includes(e.type)) console.log(JSON.stringify(e));
    }
  },

  list() {
    for (const m of store.listSessions(root)) {
      console.log(`${m.id}  ${m.status.padEnd(9)}  ${m.url ?? ''}`);
    }
  },

  init() {
    const src = fileURLToPath(new URL('../skill/SKILL.md', import.meta.url));
    const dest = path.resolve('.claude/skills/polku/SKILL.md');
    fs.mkdirSync(path.dirname(dest), { recursive: true });
    fs.copyFileSync(src, dest);
    console.log(`wrote ${path.relative(process.cwd(), dest)}`);
    const ignore = path.resolve('.gitignore');
    const current = fs.existsSync(ignore) ? fs.readFileSync(ignore, 'utf8') : '';
    if (!current.split('\n').some((l) => l.trim().replace(/\/$/, '') === '.polku')) {
      fs.writeFileSync(ignore, `${current}${current && !current.endsWith('\n') ? '\n' : ''}.polku/\n`);
      console.log('added .polku/ to .gitignore');
    }
  },
};

if (opts.help || !command || !commands[command]) {
  process.stdout.write(USAGE);
  process.exit(command && !opts.help ? 1 : 0);
}

try {
  await commands[command]();
} catch (err) {
  fail(err.code === 'EADDRINUSE' ? `port ${opts.port} is already in use` : err.message);
}
