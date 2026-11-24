# Chat messages and plan approval

Status: implemented. Written 2026-11-24.

## Problem

The widget had one way to send a request (a recording), and the agent started changing the app as soon as it claimed one. The person saw the agent's reading of the request only as the first progress step, while the agent was already editing. A misread request then cost a full implementation before the person could correct it.

Two changes address this:

- A Chat button sends a typed message without recording.
- The agent proposes a plan for every request, and changes the source only after the person approves the plan in the widget.

## Message format

A chat message could have been sent as a recording with a single `note` event and no frames, or as a separate kind of request with its own endpoint and storage. Polku uses a middle path: a chat message is a session with `kind: "chat"`, created by `POST /messages`.

A session keeps the existing pipeline. `polku wait` claims it, `polku progress` and `polku done` report on it, the widget polls `GET /sessions/<id>`, and the MCP tools find it by id. A separate kind of request would have needed a second claim loop and a second status endpoint.

A chat message is not a transcript. It has no recording start, so it has no time offsets, no `events.jsonl` and no frames. The manifest stores the text in `text`, the text is also written to `note.txt`, and the session starts with status `complete`. The brief for a chat message holds only the page address and the text, and says that there are no frames or timeline to look at. Faking a transcript (one `note` event at offset 0) would have produced a brief with an empty timeline and a missing frames directory, which invites the agent to look for material that does not exist.

## Statuses

| Status | Set by | Meaning |
| --- | --- | --- |
| `recording` | `POST /sessions` | The widget is still recording |
| `complete` | `POST /sessions/<id>/complete` or `POST /messages` | Ready for the agent to plan |
| `claimed` | `polku wait` | The agent is making a plan and must not change files |
| `planned` | `polku plan` | The widget shows the plan and waits for the person |
| `approved` | `POST /sessions/<id>/decision` with `approve` | Ready for the agent to build |
| `rejected` | `POST /sessions/<id>/decision` with `reject` | Closed; never handed to the agent again |
| `building` | `polku wait` | The agent is changing the source |
| `done` | `polku done` | Closed with a message to the person |

`polku wait` claims approved sessions before complete ones, because the person behind an approved plan has already waited once. The build brief repeats the whole request along with the approved plan, so a headless `polku watch` run, which starts without any memory of the planning run, has everything it needs. Approving clears the progress list, so steps reported while planning do not appear among the build steps.

The agent may answer with `polku done` instead of a plan, when the request is a question or cannot be done.

## Rejecting a plan

A chat message's plan has an Edit message button. It rejects the plan and reopens the text field with the original message.

A recording's plan has two buttons. Discard rejects the plan and returns to the bar, and the person records again, since a speech transcript is hard to correct by editing. Edit as text rejects the plan and opens the text field with the plan's text, so the person can correct the agent's own summary and send it as a chat message.

Both reject paths create a new session for the corrected request rather than reopening the old one. The rejected session stays on disk with status `rejected`.
