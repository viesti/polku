# Sandbox game example

A side-scrolling 2D sandbox game drawn on a canvas. The world is generated with hills, trees and caves. The player digs tiles, finds chests with loot, buys from a shop, meets slimes, and plays through a day and night cycle.

Controls: A and D to run, Space to jump, and the mouse button or Shift to dig.

A coding agent built it in the demo app from requests recorded with the Polku widget.

## Run it

Requirements: Node 20+, a JDK, the Clojure CLI and Chrome. From this directory:

```sh
npm install          # also builds packages/polku, which the app links
npm run dev          # backend + nREPL, shadow-cljs, Tailwind, polku server
```

The app opens at http://localhost:3000. It uses the same ports as the demo and the other examples (3000 and 4777), so run one at a time. Saved games live in the browser's localStorage.

## Keep changing it

Start the agent in a second terminal with `npm run agent`, press Record in the page and describe the next change. The [demo README](../../demo/README.md) explains recording in more detail.

This directory is a complete app of its own, so changes here do not affect the demo or the other examples. To discard your changes and return to the committed version, stop `npm run dev` and the agent, then run `bb reset` (needs [Babashka](https://babashka.org)).
