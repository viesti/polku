---
name: polku
description: Wait for requests that a user records in the running web app (voice, pointer, screen) and implement each one in the source as it arrives. Use when asked to start polku, watch for polku sessions, or work on recorded requests.
---

# Polku loop

A person is using the running app in their browser. The Polku widget in the page records what they say, where they point and what the screen shows, and the local Polku server writes each recording to `.polku/sessions/<id>/`. Your job is to turn each recording into a source change that they see through live reload, then wait for the next one.

The person is usually a product owner or business user, not a developer. They watch the browser, not this terminal.

## Loop

1. Start `npx polku wait` as a background command. It blocks until a recording is ready, then prints a task brief and exits. Do not poll; you are notified when it exits.
2. When it exits, read its output. The brief contains what the user typed or said, a timeline of clicks and pointer rests with the element under the pointer, and paths to screen frames.
3. Look at the screen at the moments the timeline points to. With the Polku MCP server connected, call `get_session_recording` (narrow it with `from_ms` and `to_ms`); otherwise read the JPEG files named in the brief. The last frame shows the page as the user left it. Words like "this", "here" or "that button" refer to the element under the pointer at that moment in the timeline.
4. Tell the user what you understood, in one short sentence: `npx polku progress <id> -m "Adding a table of orders under a heading."` It appears in the widget right away, before anything on the page has changed, and lets them see early when you have misread them.
5. Make the change in the application source. Follow the project's own instructions (CLAUDE.md) for where code lives and how reload works. Keep the change as small as the request; do not restart dev processes unless reload cannot pick the change up. Report progress as you go (see below).
6. Check that the change compiled and that the app still loads.
7. Tell the user what happened in one plain sentence: `npx polku done <id> -m "Added a title to the page."` The message appears in the widget in their browser. If you could not do what they asked, or had to guess, say that in the message instead.
8. Go back to step 1.

## Reporting progress

The user watches the page while you work, and early in a request the page often does not change for a while. `npx polku progress <id> -m "<step>"` shows a step in the widget; the latest one is marked as in progress and earlier ones as finished.

- Report before a step that takes a while or that the user cannot see yet: creating a database table, adding an API, waiting for a build, fixing a compile error.
- Write for the person, not a developer: "Saving orders to the database" rather than "Adding migration 002-orders.sql". One short sentence, no file names.
- Report at least every 30 seconds or so while you work, so that the widget never looks stalled. When nothing new has started, say what you are in the middle of ("Still wiring the table to the saved orders").
- Several edits towards one goal share a step. Do not report every file edit.
- Write progress and done messages in the language the user spoke or typed in.

## Notes

- Speech recognition mis-hears words. Prefer the reading that fits the frames and the app.
- If a request is ambiguous, pick the most likely reading, implement it, and state the assumption in the `done` message. The user answers by recording again.
- The MCP tools `get_context_summary` and `get_interaction_events` return the brief and the raw events, such as the full pointer path with `types: ["move"]`. Without the MCP server, `npx polku summary [id]` and `npx polku events [id] --type move` print the same.
- `npx polku wait` reads files and keeps waiting even when the ingest server is down. If the user says they sent something and nothing arrives, check `curl -s localhost:4777/health` and start `npx polku serve` in the background if it does not answer.
