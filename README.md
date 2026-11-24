# Polku

Polku lets someone using a running web app say what they want changed, and hands that request to a coding agent working in the app's source. The person records in the browser (they talk, point and click) or types a chat message. The agent receives what they said, what they pointed at and what the screen showed, and answers with a plan. Once the person approves the plan in the widget, the agent edits the code, and live reload shows the result in the same browser tab.

This repository is a local proof of concept. Design documents are numbered in the doc directory, starting with the original specification, [01-polku-proof-of-concept.md](doc/01-polku-proof-of-concept.md).

Check out the demo, since the idea is quite visual

https://github.com/user-attachments/assets/57520004-a81b-447e-ab41-f080f832a8df

## Layout

| Path | Contents |
| --- | --- |
| `packages/polku` | The `polku` npm package, written in ClojureScript under src: the browser client (compiled to an ES module) and the ingest server with its CLI (compiled to a Node script). The compiled package has no runtime dependencies |
| `demo` | A blank Clojure and ClojureScript web app that links the client, for trying the loop (see [demo/README.md](demo/README.md)) |
| `examples` | Complete copies of the demo app with what an agent built in them through Polku (see [Examples](#examples)) |

## How the loop works

```
browser                    polku serve                  Claude Code session
───────                    ───────────                  ───────────────────
widget records      ──▶    writes .polku/sessions/<id>
voice, pointer,            manifest.json, events.jsonl,
screen frames              frames/*.jpg, audio.webm,
                           transcript.txt, dom.html
or a chat message   ──▶    POST /messages writes
                           manifest.json, note.txt
"Stop & send"/"Send"──▶    status: complete      ──▶    `polku wait` exits with a plan brief
                                                        agent reads brief and frames
widget shows plan   ◀──    status: planned       ◀──    `polku plan <id> -m "..."`
"Approve"           ──▶    status: approved      ──▶    `polku wait` exits with a build brief
                                                        agent edits source, app live-reloads
widget lists steps  ◀──    progress in manifest  ◀──    `polku progress <id> -m "..."`
widget shows        ◀──    status: done          ◀──    `polku done <id> -m "..."`
the message
```

1. **Capture.** The widget asks for the microphone and for sharing the current tab. It records pointer positions, clicks, the element the pointer rests on, scrolls, window resizes, navigation and input activity (lengths only, never typed text). Pointer coordinates are relative to the viewport, like the frames. Speech is transcribed in the browser with the Web Speech API, and each utterance is timestamped at the moment it began. Screen frames are JPEG stills taken when the picture changes and after each click. All events carry a millisecond offset `t` from the start of the recording, and frame files are named by the same offset.
2. **Ingest.** `polku serve` listens on 127.0.0.1:4777, accepts requests from localhost origins and writes each session to `.polku/sessions/<id>/` in the directory it was started from.
   A chat message is a session of kind `chat` without a recording. The Chat button opens a text field, and Send posts the text with the page address to `POST /messages`, which stores it as complete at once. Its brief holds only the text and the page.
3. **Handoff.** `polku wait` blocks until a session needs the agent, claims it, writes the brief to `.polku/CURRENT_TASK.md` and prints it. The brief merges speech, clicks and pointer rests into one timeline and names the frame that shows each moment.
4. **Plan.** A newly claimed session (status `claimed`) asks the agent for a plan and forbids changing files. The agent sends the plan with `polku plan`, and the widget shows it with an Approve button. Rejecting a chat message's plan reopens the text field with the message for editing. Rejecting a recording's plan either discards it, so the person records again, or opens the plan text in the chat field for editing ("Edit as text"). A rejected session is never handed to the agent again. Approving makes the session `approved`, and the next `polku wait` claims it as `building`, with the approved plan in the brief. Approved plans are claimed before new requests.
5. **Change.** The agent edits the source and relies on the app's own live reload. When a request is about a place, a size or an order on the page, the agent also reads the pointer path around the words that describe it, since a gesture such as circling a gap shows only there. Conventions fill in what the gesture leaves open. Early in a request the page often shows nothing new for a while, so the agent reports each part of the work it starts and each decision the person will see with `polku progress`. Problems in its own work are not reported. The widget lists the steps, with the latest marked as in progress.
6. **Report.** `polku done` stores a one-sentence message that the widget shows to the person. The agent writes progress and done messages in the language the person used.

## Try it with the demo

The same instructions are in [demo/README.md](demo/README.md).

Requirements: Node 20+, a JDK, the Clojure CLI, and Docker. The demo runs PostgreSQL in a container on port 5433 (demo/docker-compose.yml).

```sh
cd demo
npm install          # also builds packages/polku, which the demo links
npm run dev          # PostgreSQL, backend + nREPL, shadow-cljs, Tailwind, polku server
```

`npm run dev` opens http://localhost:3000 in the default browser once the backend answers and the first frontend build is done (set `NO_OPEN=1` to skip that). Recording needs Chrome, so open the address there if another browser is the default. The page is blank apart from the Record button in the lower right corner.

In a second terminal, start the agent in the demo directory:

```sh
cd demo
npm run agent
```

`npm run agent` opens Claude Code with a first prompt that starts the Polku loop. In a Claude Code session that is already open, type `/polku` instead. Until one of the two has happened, recordings wait in .polku/sessions with status `complete`.

Then, in the browser: press Record, allow the microphone and tab sharing, say or type what you want ("add a heading that says Orders, and under it a table of orders with customer and amount"), and press Stop & send. Or press Chat, type the request and press Send. The widget shows the agent's plan, and the agent starts changing the app only after you press Approve. The widget then lists the steps and finally what the agent did.

To return the demo to the blank page, stop `npm run dev` and the agent, then run `bb reset` in the demo directory (needs [Babashka](https://babashka.org)). It lists the uncommitted changes under src and resources, asks for confirmation, then discards them, recreates the database and deletes the recordings.

To run the agent headless instead, with one fresh `claude -p` run for each plan and another for each approved plan:

```sh
npx polku watch -- claude -p --permission-mode acceptEdits
```

## Examples

The examples directory holds apps that an agent built from the blank demo through recorded requests. Each one is a complete copy of the demo app, so it can be run, changed through Polku or rewritten without affecting the demo or the other examples.

| Example | What it is |
| --- | --- |
| [mood-game](examples/mood-game) | A small game in which everyday activities change a character's mood |
| [terraria-clone](examples/terraria-clone) | A side-scrolling 2D sandbox game with digging, caves, chests, a shop and slimes |
| [shopping-list](examples/shopping-list) | Shopping lists with icons per item, stored in PostgreSQL |

Run an example the same way as the demo, from its own directory. They all use ports 3000, 4777 and 5433, so run one at a time.

The demo and each example carry their own copy of the Polku skill in .claude/skills/polku. After changing packages/polku/skill/SKILL.md, run `npx polku init` in each of them, or copy the file over.

## Adding Polku to another app

```sh
npm install --save-dev polku     # from this repo: build packages/polku, then npm install --save-dev ../packages/polku
npx polku init                   # installs .claude/skills/polku, adds the MCP server to .mcp.json, ignores .polku/
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

Options: `endpoint`, `screen` and `audio` (both default to true), `lang` (speech recognition language, defaults to the browser language), `languages` (the speech languages offered in the widget, defaults to `lang` followed by the browser's preferred languages) and `frameInterval` (milliseconds between frame checks, default 2000). `init` returns a controller with `start`, `stop(note)`, `cancel`, `dismiss`, `setLang(tag)`, `getState` and `destroy`.

The Web Speech API recognizes one language at a time and cannot detect it. In browsers that have `SpeechRecognition.available()`, the widget drops the languages it reports as unavailable. When more than one language remains, the recording panel shows a language selector. Switching restarts recognition in the new language, and the widget remembers the choice in localStorage for later recordings. Each speech event records the language it was recognized in, and the brief names it.

## CLI

```
polku serve [--port 4777] [--origin <url>]   start the ingest server
polku wait [--timeout <s>]                   block until a session is ready, print its brief
polku watch -- <cmd...>                      run <cmd> with the brief on stdin for each claimed session
polku plan [id] -m <text>                    show the plan in the user's browser for approval
polku progress [id] -m <text>                show the step the agent is on in the user's browser
polku done [id] -m <text>                    finish a session and message the user
polku summary [id]                           print a session's brief
polku events [id] [--type <t>]               print raw events as JSON lines
polku list                                   list sessions
polku mcp                                    serve sessions as MCP tools over stdio
polku init                                   install the Claude Code skill and the MCP server entry
```

## MCP tools

`polku mcp` is a Model Context Protocol server over stdio. It reads the same `.polku` directory as the other commands, so it needs to start in the app's directory. `polku init` registers it in the project's .mcp.json, and Claude Code asks once whether to trust it.

| Tool | Returns |
| --- | --- |
| `get_context_summary` | The brief for a session: what the user typed and said, the timeline, and where the artifacts are |
| `get_session_recording` | Screen frames as images, each labelled with its offset. `from_ms`, `to_ms` and `max_frames` choose which |
| `get_interaction_events` | Raw events as JSON lines, filtered by `types`, `from_ms`, `to_ms` and `limit`. Pointer positions are left out unless `types` asks for `move` |
| `list_sessions` | Sessions with status, duration, frame count and page |

Every tool takes an optional `session_id` and defaults to the latest claimed session. The tools only read. Claiming a session and reporting it done stay with `polku wait` and `polku done`.

## Building the client

```sh
cd packages/polku
npm install
npm run build        # shadow-cljs release → dist/client.js (browser) and dist/polku.cjs (CLI)
```

## Not built yet

- **Local transcription.** Speech goes through the browser's Web Speech API, which in Chrome sends audio to Google and needs the language chosen up front. The raw `audio.webm` is stored, so a local Whisper pass on the server can replace it, which would also detect the language and handle a mix of languages in one recording.
- **Isolation.** The agent edits the working tree and the one dev database directly. Branchable databases and per-session environments from the spec are future work.
- **Video.** Frames are stills rather than a continuous recording.
