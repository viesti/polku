import path from 'node:path';
import * as store from './store.js';

const { fmtT } = store;

function describeTarget(el) {
  if (!el) return 'the page';
  const text = el.text ? ` "${el.text}"` : '';
  return `\`${el.selector}\`${text}`;
}

function describeEvent(e) {
  switch (e.type) {
    case 'speech':
      return `SAID: "${e.text}"`;
    case 'click':
      return `clicked ${describeTarget(e.target)} at (${e.x}, ${e.y})`;
    case 'dwell':
      return `pointer resting on ${describeTarget(e.target)} at (${e.x}, ${e.y})`;
    case 'input':
      return `typed into ${describeTarget(e.target)} (${e.length} characters)`;
    case 'scroll':
      return `scrolled to (${e.x}, ${e.y})`;
    case 'nav':
      return `navigated to ${e.url}`;
    case 'resize':
      return `viewport resized to ${e.w}x${e.h}`;
    default:
      return null;
  }
}

/** Returns the latest frame taken at or before `t`, or the first frame when none is that early. */
function frameAt(frames, t) {
  let found = frames[0];
  for (const f of frames) {
    if (f.t <= t + 250) found = f;
    else break;
  }
  return found;
}

/** Builds the Markdown brief an agent reads to act on one session. */
export function buildSummary(root, id) {
  const m = store.readManifest(root, id);
  const dir = store.sessionDir(root, id);
  const rel = (file) => path.relative(process.cwd(), file) || '.';
  const events = store.readEvents(root, id);
  const frames = store.listFrames(root, id);
  const note = events.find((e) => e.type === 'note')?.text;
  const speech = events.filter((e) => e.type === 'speech');
  const moves = events.filter((e) => e.type === 'move').length;

  const out = [];
  out.push(`# Polku session ${m.id}`, '');
  out.push(`- Page: ${m.url ?? 'unknown'}${m.title ? ` ("${m.title}")` : ''}`);
  if (m.viewport) out.push(`- Viewport: ${m.viewport.w}x${m.viewport.h} CSS pixels. Event coordinates use this space; frames show the same area, possibly scaled.`);
  out.push(`- Recorded: ${m.createdAt}, ${fmtT(m.durationMs ?? 0)} long`);
  out.push(`- Session directory: ${rel(dir)}`, '');

  out.push('## What the user asked for', '');
  if (note) out.push('Typed note:', '', `> ${note.replace(/\n/g, '\n> ')}`, '');
  if (speech.length) {
    out.push('Spoken (speech recognition output, which may contain mis-heard words):', '');
    out.push(`> ${speech.map((e) => e.text).join(' ')}`, '');
  }
  if (!note && !speech.length) {
    out.push('The user gave neither a note nor recognizable speech. Infer the intent from the timeline and frames, and say so in the completion message if it stays unclear.', '');
  }

  const timeline = events.map((e) => [e, describeEvent(e)]).filter(([, d]) => d);
  if (timeline.length) {
    out.push('## Timeline', '');
    out.push('Offsets are minutes:seconds from the start of the recording. A frame listed beside an entry shows the screen at that moment.', '');
    let lastFrame;
    for (const [e, text] of timeline) {
      const frame = frames.length ? frameAt(frames, e.t) : null;
      const showFrame = frame && frame !== lastFrame;
      if (showFrame) lastFrame = frame;
      out.push(`- ${fmtT(e.t)} ${text}${showFrame ? ` — frame ${rel(frame.file)}` : ''}`);
    }
    out.push('');
  }

  out.push('## Artifacts', '');
  if (frames.length) {
    out.push(`- ${frames.length} ${frames.length === 1 ? 'frame' : 'frames'} in ${rel(path.join(dir, 'frames'))}, named by millisecond offset. The last one, ${rel(frames.at(-1).file)}, shows the page when the user stopped recording.`);
  } else {
    out.push('- No frames were captured (screen capture was off or denied).');
  }
  out.push(`- ${rel(path.join(dir, 'events.jsonl'))} holds every event, including the ${moves} pointer positions left out of the timeline.`);
  if (m.audioFile) out.push(`- ${rel(path.join(dir, m.audioFile))} is the raw microphone recording.`);
  out.push(`- ${rel(path.join(dir, 'dom.html'))} is the page DOM when the user stopped recording.`, '');
  return out.join('\n');
}

/** Builds the full prompt for an agent: the session brief followed by how to finish. */
export function buildTask(root, id) {
  return [
    buildSummary(root, id),
    '## What to do',
    '',
    '1. Read the frames that matter (they are images) to see what the user was looking at and pointing to.',
    '2. Change the application source to do what the user asked. The app is running with live reload, so saved changes appear in the user\'s browser without a restart.',
    '3. Check that the change compiled and the app still works.',
    `4. Report back to the user's browser with a one-sentence, non-technical message: \`npx polku done ${id} -m "<what changed>"\`.`,
    '',
  ].join('\n');
}
