# Polku demo

This is a blank Clojure and ClojureScript web app with the Polku widget built in. You describe in the browser what you want, and a coding agent builds it in this directory while you watch the page change. The source starts empty on purpose. The [examples](../examples) directory has apps that were built this way.

## Requirements

Node 20+, a JDK, the Clojure CLI, and Docker. The app runs PostgreSQL in a container on port 5433 (docker-compose.yml). Recording needs Chrome.

## Start the app

```sh
npm install          # also builds packages/polku, which the app links
npm run dev          # PostgreSQL, backend + nREPL, shadow-cljs, Tailwind, polku server
```

`npm run dev` opens http://localhost:3000 in the default browser once the backend answers and the first frontend build is done (set `NO_OPEN=1` to skip that). If Chrome is not the default browser, open the address there. The page is blank apart from the Record button in the lower right corner.

## Start the agent

In a second terminal in this directory:

```sh
npm run agent
```

`npm run agent` opens Claude Code with a first prompt that starts the Polku loop. In a Claude Code session that is already open, type `/polku` instead. Until one of the two has happened, recordings wait in .polku/sessions with status `complete`.

To run the agent headless instead, with one fresh `claude -p` run per request:

```sh
npx polku watch -- claude -p --permission-mode acceptEdits
```

## Record a request

1. Press Record and allow the microphone and tab sharing.
2. Say or type what you want, for example "add a heading that says Orders, and under it a table of orders with customer and amount". Point at the page while you talk: the agent reads where the pointer was when you said "here" or "this".
3. Press Stop & send.

The widget shows when the agent picks the request up, the steps it works through and a message when it is done. The change appears in the page through live reload.

## Start over

Stop `npm run dev` and the agent, then run `bb reset` (needs [Babashka](https://babashka.org)). It lists the uncommitted changes under src and resources, asks for confirmation, then discards them, recreates the database and deletes the recordings.

## For the agent

CLAUDE.md describes the stack, where code goes and how changes reach the page. The Polku skill is in .claude/skills/polku and the MCP server entry in .mcp.json, both installed by `npx polku init`.
