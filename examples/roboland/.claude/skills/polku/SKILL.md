---
name: polku
description: Wait for requests that a user records or types in the running web app (voice, pointer, screen, chat), propose a plan for each one, and implement it in the source once the user approves the plan. Use when asked to start polku, watch for polku sessions, or work on recorded requests.
---

# Polku loop

A person is using the running app in their browser. The Polku widget in the page records what they say, where they point and what the screen shows, or takes a typed chat message, and the local Polku server writes each request to `.polku/sessions/<id>/`. Your job is to propose a plan for each request, and once the person approves it in the widget, to turn it into a source change that they see through live reload.

Every request goes through two turns of `npx polku wait`. The first brief asks for a plan, and you must not change any files during it. The second brief arrives only if the person approves the plan, and repeats the request together with the approved plan. A rejected plan never comes back; the person sends a new request instead.

The person is usually a product owner or business user, not a developer. They watch the browser, not this terminal.

## Loop

1. Start `npx polku wait` as a background command. It blocks until a request needs you, then prints a task brief and exits. Do not poll; you are notified when it exits.
2. When it exits, read its output. The brief says whether to plan or to build:
   - "Plan the change" means a new request. Go to step 3.
   - "Approved plan" means the person approved your plan. Go to step 7.

Planning a request:

3. Read the request. A recording's brief contains what the user typed or said, a timeline of clicks and pointer rests with the element under the pointer, and paths to screen frames. A chat message's brief contains the typed text, the page it was sent from and any screenshots the user attached.
4. For a recording, look at the screen at the moments the timeline points to. With the Polku MCP server connected, call `get_session_recording` (narrow it with `from_ms` and `to_ms`); otherwise read the JPEG files named in the brief. The last frame shows the page as the user left it. Words like "this", "here" or "that button" refer to the element under the pointer at that moment in the timeline. For a chat message with screenshots, read each screenshot; the red strokes are the user's own drawing and mark what words like "this" or "here" refer to. When a recording involves a position, a size, an area or an order on the page, read the pointer path around the words that describe it (see "Reading the pointer" below).
5. Decide how you would do it. Read the source as much as you need, but do not edit anything.
6. Send the plan: `npx polku plan <id> -m "Adding a table of orders under a heading, newest orders first."` Write two to four short sentences that say what will change on the page, and include the choices the person left open, such as what an enemy looks like or how it moves. The widget shows the plan with buttons to approve or reject it. If the request is a question or something you cannot do, answer with `npx polku done <id> -m "<answer>"` instead of a plan. Go back to step 1; do not wait for the answer in any other way.

Building an approved plan:

7. Make the change in the application source, as the approved plan describes. Follow the project's own instructions (CLAUDE.md) for where code lives and how reload works. Keep the change as small as the request; do not restart dev processes unless reload cannot pick the change up. Report progress as you go (see below).
8. Check that the change compiled and that the app still loads.
9. Update the summary of the unsaved changes (see below).
10. Tell the user what happened in one plain sentence: `npx polku done <id> -m "Added a title to the page."` The message appears in the widget in their browser. If you could not do what the plan said, or had to deviate from it, say that in the message.
11. Go back to step 1.

## Summary of the unsaved changes

The widget's History panel lists the changes that are not yet committed, with a summary from you that the user can edit and save. Saving commits every change with that text as the message and pushes the branch to origin, so the summary has to describe all the uncommitted changes, not just the last request.

- After each approved change, run `npx polku changes` to see the uncommitted files and the summary you stored before, then store a new one: `npx polku changes -m "<summary>"`.
- Write it as a commit message in the user's language: a short first line that names the change (such as "Add robbers that walk the surface"), a blank line, then a few plain sentences about what changed for the user. Merge the new change into the earlier summary rather than appending a log of requests, and leave out changes that were undone.
- A commit made in any other way, such as by you when the user asks, makes the stored summary stale, and the panel stops showing it. Store a new one when there are new changes.
- The user may save between your requests, and saving clears the summary. Do not rebuild the summary from the one you wrote earlier; describe what `npx polku changes` lists now. `npx polku changes -m` refuses a summary that starts like the last commit's message, because it repeats changes that are already committed; rewrite it to cover only the listed files. Pass `--force` only when the new changes really begin the same way.
- `npx polku done` reminds you when the summary is missing or older than your latest edit. Store a new one before you go back to waiting.

## Reading the pointer

The brief lists clicks and pointer rests, which name the element the user meant. A gesture that points at a place rather than an element shows only in the full pointer path: circling a gap between two elements, tracing the outline of an area, sweeping along a row, or moving from one spot to another while saying "move it from here to there".

- Find the words that refer to a place ("here", "about here", "next to this", "between these", "this big", "over there") and their time in the timeline. Read the move events from about a second before to a second after: `get_interaction_events` with `types: ["move"]` and `from_ms`/`to_ms`, or `npx polku events <id> --type move` and keep the lines whose `t` falls in that window.
- Map the coordinates onto the frame from the same moment. Pointer coordinates are relative to the browser viewport, as the frames are, so they apply to the frame directly even when the page is scrolled. Note which elements the path circles, which edges it stays inside, and where it starts and ends. A tight loop marks a point, a rectangle-like path marks an area, and a path between two elements marks a gap or an insertion point.
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
- Write plans, progress and done messages in the language the user spoke or typed in.

## Notes

- Edit source files with the Edit and Write tools, not with shell commands such as `sed -i`. A shell edit can stop for a permission prompt in this terminal, and the user, who watches only the browser, waits without knowing why.
- Speech recognition mis-hears words. Prefer the reading that fits the frames and the app.
- If a request is ambiguous, pick the most likely reading and state the assumption in the plan. The user rejects the plan if the reading is wrong.
- The MCP tools `get_context_summary` and `get_interaction_events` return the brief and the raw events, such as the full pointer path with `types: ["move"]`. Without the MCP server, `npx polku summary [id]` and `npx polku events [id] --type move` print the same.
- `npx polku wait` reads files and keeps waiting even when the ingest server is down. If the user says they sent something and nothing arrives, check `curl -s localhost:4877/health` and start `npx polku serve --port 4877` in the background if it does not answer.
