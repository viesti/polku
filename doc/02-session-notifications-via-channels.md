# Notifying the agent of new sessions through Claude Code channels

Status: proposal, not implemented. Written 2026-11-14.

## Problem

A recording becomes useful when a coding agent starts working on it. Claude Code acts only when something gives it a turn, so today the Polku skill tells the agent to run `polku wait` as a background command. The command exits when a session is complete, and Claude Code re-invokes the agent on that exit.

This works on every account, but it has costs:

- The agent has to restart `polku wait` after every session. If it forgets, recordings wait unnoticed.
- The loop only starts after a first prompt (`/polku` or `npm run agent`).
- The handoff uses the CLI while the session data is available through MCP tools, so the agent uses two interfaces for one job.

The question for this document is whether the Polku MCP server can tell the agent about a new session directly.

## What Claude Code does with MCP notifications

The facts in this section come from the Claude Code documentation as read on 2026-11-14. The sources are listed at the end.

Standard MCP notifications do not give the agent a turn. Claude Code documents `list_changed` (tools, prompts, resources) and progress notifications, and both only refresh client state. The documentation pages read for this document do not say what Claude Code does with resource subscriptions (`notifications/resources/updated`) or logging (`notifications/message`).

MCP tasks can start a turn: if Claude is idle when a task's result arrives, it starts on the result unprompted. The agent must have started the task first, so tasks do not remove the need for a first prompt or for re-arming after each session.

Channels are the feature built for this case. They are a research preview.

| Aspect | Documented behaviour |
| --- | --- |
| Declaration | The server adds `'claude/channel': {}` under `capabilities.experimental`. Declaring `tools: {}` as well makes the channel two-way |
| Event | The server sends `notifications/claude/channel` with `params.content` (a string) and `params.meta` (string values, keys limited to identifier characters) |
| Delivery | Claude Code inserts the event into the running session as a `<channel source="..." key="value">` block, and an idle session starts a turn on it. Events queue and are handled in order |
| Acknowledgement | None. Events are dropped silently when channels are blocked |
| Start flag | `claude --dangerously-load-development-channels server:<name>` for a locally developed server, with a confirmation dialog. `--channels` accepts allowlisted plugins only |
| Modes | Interactive sessions only. The flag is ignored with `claude -p` and the Agent SDK |
| Accounts | Needs a claude.ai login or a Console API key. Unavailable on Bedrock, Vertex and Foundry. Team and Enterprise organisations need an owner to enable channels |
| Protocol | On the newer MCP runtime a channel server is registered only when `MCP_PROTOCOL_NEGOTIATION=legacy` is set |

The minimum Claude Code version for channels is not stated in the pages read.

## Proposal

Add channel support to `polku mcp` as an opt-in, and keep `polku wait` as the default that works everywhere.

### Announce, then let the agent claim

A channel event has no acknowledgement, so the server cannot know whether the agent received it. If the server marked a session `claimed` when it sent the event, a dropped event would leave the session stuck and the widget would tell the user that an agent is working on it.

The server therefore only announces. The agent claims through a tool call, which the server can observe:

1. `polku mcp --channel` polls `.polku/sessions` once a second, as `polku wait` does.
2. For each session with status `complete`, it sends one channel event. The content is a single sentence, for example "A new recording is ready." The meta carries `session_id` and `event` with the value `session_ready`.
3. The agent calls a new tool, `claim_session`. The tool marks the session `claimed` and returns the task brief, which is the text `polku wait` prints today.
4. The agent does the work and calls a second new tool, `complete_session`, with the message for the user. It does the same as `polku done`.

A session that is still `complete` some time after its announcement is announced again. The interval should be long enough that an agent busy with an earlier session is not interrupted repeatedly; one minute is a starting point. Sessions that are already `complete` when the server starts are announced at startup, which covers recordings made before the agent was running.

`claim_session` takes an optional `session_id` and defaults to the oldest `complete` session. It returns an error result when the session is already claimed, which keeps a second announcement or a parallel `polku wait` from producing duplicate work.

### Tool surface

The server gains two tools that write, next to the four that read:

| Tool | Effect |
| --- | --- |
| `claim_session` | Marks a `complete` session `claimed` and returns its task brief |
| `complete_session` | Marks a session `done` and stores the message shown in the user's browser |

Both are useful without channels. An agent in any MCP client can then run the whole loop through MCP, with `polku wait` or its own polling of `list_sessions` as the trigger.

### Enabling it

- `polku mcp` accepts `--channel`. Without the flag the server neither declares the capability nor sends events, so a client that does not know channels sees no difference.
- `polku init --channel` writes the flag and `MCP_PROTOCOL_NEGOTIATION=legacy` into the project's .mcp.json entry.
- The demo's `npm run agent` starts `claude --dangerously-load-development-channels server:polku`. The first prompt is no longer needed, because the startup announcement of waiting sessions gives the agent its first turn. This needs checking, since an announcement can only arrive after the user has confirmed the dialog.
- The skill describes both triggers. With channels, the agent waits for an event and calls `claim_session`. Without them, it runs `polku wait` as now.

`polku wait` and the channel must not run in the same session, because they compete for the same recordings. The claim is exclusive either way, so the result of running both is confusion about which path picked a session up, not duplicate work.

## Risks and open questions

- **Preview feature.** The capability name, the payload and the start flag may change. Keeping `polku wait` as the default limits the damage.
- **The start flag.** Asking a business user's setup to pass a flag with "dangerously" in its name is a poor fit for the product. It is acceptable for the proof of concept, where a developer starts the agent.
- **Silent drops.** Organisation policy or a missing flag makes events vanish without an error. `polku mcp --channel` cannot detect this. The repeated announcement does not help when every event is dropped, so the widget should eventually tell the user that no agent has picked the recording up.
- **Events during a turn.** The documentation says events queue. Whether a queued event interrupts a long turn or waits for it to end decides how the repeat interval should be set, and needs a test.
- **Content in the event.** Channel content enters the agent's context like a user message. The announcement carries no recorded speech, only a session id, so what the user said reaches the agent through `claim_session` as tool output.
- **Other agents.** Channels are specific to Claude Code. The two new tools are plain MCP and are the portable part of this proposal.

## How to verify

1. Start the demo and `claude` with the channel flag, record a session, and confirm that the idle agent starts without a prompt and that the widget moves from waiting to working to done.
2. Record a second session while the agent is still working on the first, and observe when the second announcement is handled.
3. Start `claude` without the flag, record a session, and confirm that nothing breaks and that `polku wait` still picks it up.
4. Record a session before starting `claude`, then start it, and confirm that the startup announcement leads to a claim.

## Sources

- https://code.claude.com/docs/en/channels.md
- https://code.claude.com/docs/en/channels-reference.md
- https://code.claude.com/docs/en/mcp.md
