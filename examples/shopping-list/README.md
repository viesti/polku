# Shopping list example

A shopping list app with several named lists. Items can be added, checked off and deleted, and each item gets an icon chosen from its name (milk, eggs and so on, from Lucide). Lists and items are stored in PostgreSQL, so the app shows the full stack: a migration, HugSQL queries, API routes and the UI.

A coding agent built it in the demo app from requests recorded with the Polku widget.

## Run it

Requirements: Node 20+, a JDK, the Clojure CLI, Docker and Chrome. From this directory:

```sh
npm install          # also builds packages/polku, which the app links
npm run dev          # PostgreSQL, backend + nREPL, shadow-cljs, Tailwind, polku server
```

The app opens at http://localhost:3000. It uses the same ports as the demo and the other examples (3000, 4777 and 5433), so run one at a time. Each one has its own database.

## Keep changing it

Start the agent in a second terminal with `npm run agent`, press Record in the page and describe the next change. The [demo README](../../demo/README.md) explains recording in more detail.

This directory is a complete app of its own, so changes here do not affect the demo or the other examples. To discard your changes and return to the committed version, stop `npm run dev` and the agent, then run `bb reset` (needs [Babashka](https://babashka.org)).
