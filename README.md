# Polku

Polku lets someone using a running web app say what they want changed, and hands that request to a coding agent working in the app's source. The person records in the browser: they talk, point and click. The agent receives what they said, what they pointed at and what the screen showed, edits the code, and live reload shows the result in the same browser tab.

This repository is a local proof of concept. The original specification is in [the spec document](Polku%20-%20Autonomous%20Self-Modifying%20Agent%20Loop%20Spec%20%26%20Transcripts.md).

## Layout

| Path | Contents |
| --- | --- |
| `packages/polku` | The `polku` npm package: browser client (ClojureScript, compiled to an ES module), ingest server and CLI (Node, no dependencies) |
| `demo` | A blank Clojure and ClojureScript web app that links the client, for trying the loop |

## How the loop works

```
browser                    polku serve                  Claude Code session
───────                    ───────────                  ───────────────────
widget records      ──▶    writes .polku/sessions/<id>
voice, pointer,            manifest.json, events.jsonl,
screen frames              frames/*.jpg, audio.webm,
                           transcript.txt, dom.html
"Stop & send"       ──▶    status: complete      ──▶    `polku wait` exits with a task brief
                                                        agent reads brief and frames,
                                                        edits source, app live-reloads
widget shows        ◀──    status: done          ◀──    `polku done <id> -m "..."`
the message
```

1. **Capture.** The widget asks for the microphone and for sharing the current tab. It records pointer positions, clicks, the element the pointer rests on, scrolls, navigation and input activity (lengths only, never typed text). Speech is transcribed in the browser with the Web Speech API, and each utterance is timestamped at the moment it began. Screen frames are JPEG stills taken when the picture changes and after each click. All events carry a millisecond offset `t` from the start of the recording, and frame files are named by the same offset.
2. **Ingest.** `polku serve` listens on 127.0.0.1:4777, accepts requests from localhost origins and writes each session to `.polku/sessions/<id>/` in the directory it was started from.
3. **Handoff.** `polku wait` blocks until a session is complete, claims it, writes the brief to `.polku/CURRENT_TASK.md` and prints it. The brief merges speech, clicks and pointer rests into one timeline and names the frame that shows each moment.
4. **Change.** The agent edits the source and relies on the app's own live reload.
5. **Report.** `polku done` stores a one-sentence message that the widget shows to the person.

## Try it with the demo

Requirements: Node 20+, a JDK, the Clojure CLI, and Docker. The demo runs PostgreSQL in a container on port 5433 (demo/docker-compose.yml).

```sh
cd demo
npm install
npm run dev          # PostgreSQL, backend + nREPL, shadow-cljs, Tailwind, polku server
```

Open http://localhost:3000 in Chrome. The page is blank apart from the Record button in the lower right corner.

In a second terminal, start the agent in the demo directory:

```sh
cd demo
claude
> /polku
```

Then, in the browser: press Record, allow the microphone and tab sharing, say or type what you want ("add a heading that says Orders, and under it a table of orders with customer and amount"), and press Stop & send. The widget shows when the agent picks the request up and what it did.

To run the agent headless instead, with one fresh `claude -p` run per request:

```sh
npx polku watch -- claude -p --permission-mode acceptEdits
```

## Adding Polku to another app

```sh
npm install --save-dev polku     # from this repo: npm install --save-dev ../packages/polku
npx polku init                   # installs .claude/skills/polku and ignores .polku/
npx polku serve                  # alongside the app's dev server
```

JavaScript:

```js
import { init } from 'polku';

if (import.meta.env.DEV) init({ endpoint: 'http://localhost:4777' });
```

ClojureScript (with `packages/polku/src` on the source path, or the package as a `deps.edn` dependency):

```clojure
(ns app.core (:require [polku.client.core :as polku]))

(when ^boolean goog.DEBUG
  (polku/init {:endpoint "http://localhost:4777"}))
```

Options: `endpoint`, `screen` and `audio` (both default to true), `lang` (speech recognition language, defaults to the browser language) and `frameInterval` (milliseconds between frame checks, default 2000). `init` returns a controller with `start`, `stop(note)`, `cancel`, `dismiss`, `getState` and `destroy`.

## CLI

```
polku serve [--port 4777] [--origin <url>]   start the ingest server
polku wait [--timeout <s>]                   block until a session is ready, print its brief
polku watch -- <cmd...>                      run <cmd> with the brief on stdin for each session
polku done [id] -m <text>                    finish a session and message the user
polku summary [id]                           print a session's brief
polku events [id] [--type <t>]               print raw events as JSON lines
polku list                                   list sessions
polku init                                   install the Claude Code skill
```

## Building the client

```sh
cd packages/polku
npm install
npm run build        # shadow-cljs release → dist/client.js
```

## Not built yet

- **`polku-mcp`.** The spec lists MCP tools (`get_session_recording`, `get_interaction_events`, `get_context_summary`). The CLI commands `summary` and `events` cover the same data for now.
- **Local transcription.** Speech goes through the browser's Web Speech API, which in Chrome sends audio to Google. The raw `audio.webm` is stored, so a local Whisper pass on the server can replace it.
- **Isolation.** The agent edits the working tree and the one dev database directly. Branchable databases and per-session environments from the spec are future work.
- **Video.** Frames are stills rather than a continuous recording.
