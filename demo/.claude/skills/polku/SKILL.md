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
4. When the request involves a position, a size, an area or an order on the page, read the pointer path around the words that describe it (see "Reading the pointer" below) before you decide.
5. Once you have decided how to do it, tell the user your plan in one or two short sentences: `npx polku progress <id> -m "Adding a table of orders under a heading, newest orders first."` Include the choices they left open, such as what an enemy looks like or how it moves, because they cannot see those until the end. The message appears in the widget before anything on the page has changed, and lets them see early when you have misread them.
6. Make the change in the application source. Follow the project's own instructions (CLAUDE.md) for where code lives and how reload works. Keep the change as small as the request; do not restart dev processes unless reload cannot pick the change up. Report progress as you go (see below).
7. Check that the change compiled and that the app still loads.
8. Tell the user what happened in one plain sentence: `npx polku done <id> -m "Added a title to the page."` The message appears in the widget in their browser. If you could not do what they asked, or had to guess, say that in the message instead.
9. Go back to step 1.

## Reading the pointer

The brief lists clicks and pointer rests, which name the element the user meant. A gesture that points at a place rather than an element shows only in the full pointer path: circling a gap between two elements, tracing the outline of an area, sweeping along a row, or moving from one spot to another while saying "move it from here to there".

- Find the words that refer to a place ("here", "about here", "next to this", "between these", "this big", "over there") and their time in the timeline. Read the move events from about a second before to a second after: `get_interaction_events` with `types: ["move"]` and `from_ms`/`to_ms`, or `npx polku events <id> --type move` and keep the lines whose `t` falls in that window.
- Map the coordinates onto the frame from the same moment. Note which elements the path circles, which edges it stays inside, and where it starts and ends. A tight loop marks a point, a rectangle-like path marks an area, and a path between two elements marks a gap or an insertion point.
- Check the path against the convention for that kind of element. Conventions are still the right default. Use one to fill in what the gesture leaves open (exact spacing, alignment, how the element looks), and use one when the path is too scattered to read.
- When the path and the convention disagree, follow the path and mention the placement in the plan message, so the user can correct you early if you misread the gesture.
- When you placed something by convention because the path did not settle it, say so in the `done` message.

## Reporting progress

The user watches the page while you work, and early in a request the page often does not change for a while. `npx polku progress <id> -m "<step>"` shows a step in the widget; the latest one is marked as in progress and earlier ones as finished.

- Report when you start each part of the plan that the user would recognise ("Drawing the slimes", "Making the slimes hop towards the player", "Letting the pickaxe hit slimes"), and when you start checking the result in the browser.
- Report a decision as soon as you make it, if it changes what the user will see ("Slimes appear only deep in the caves, so the surface stays safe").
- When you are about to write a status line in this terminal, check whether it would interest the user. If it would, send it with `npx polku progress` as well. The user does not see this terminal.
- Do not report problems in your own work, such as compile errors, a crash you caused, a failed check or a retry. The user sees only what is being built. If fixing takes a while, describe the part you are finishing ("Finishing the slimes' movement").
- Write for the person, not a developer: "Saving orders to the database" rather than "Adding migration 002-orders.sql". One short sentence, no file names.
- Several edits towards one goal share a step. Do not report every file edit.
- Write progress and done messages in the language the user spoke or typed in.

## Notes

- Speech recognition mis-hears words. Prefer the reading that fits the frames and the app.
- If a request is ambiguous, pick the most likely reading, implement it, and state the assumption in the `done` message. The user answers by recording again.
- The MCP tools `get_context_summary` and `get_interaction_events` return the brief and the raw events, such as the full pointer path with `types: ["move"]`. Without the MCP server, `npx polku summary [id]` and `npx polku events [id] --type move` print the same.
- `npx polku wait` reads files and keeps waiting even when the ingest server is down. If the user says they sent something and nothing arrives, check `curl -s localhost:4777/health` and start `npx polku serve` in the background if it does not answer.
