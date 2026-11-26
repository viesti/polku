# Sandbox game example

A side-scrolling 2D sandbox game drawn on a canvas. The world is generated with hills, trees and caves. The player digs tiles, finds chests with loot, buys from shops, meets slimes, fights the Lord of Goo, and plays through a day and night cycle. Further on are the Slime Dimension, quests, Fluffies and their houses, and outer space with aliens and a star shop.

Controls: A and D to run, Space or W to jump or climb a rope, S to climb down, and the mouse button or Shift to dig. E opens a shop, takes a quest or interacts with a Fluffy nearby, 1 to 0 pick a bag slot, and M opens the menu.

A coding agent built it in the demo app from requests recorded with the Polku widget.

## Run it

Requirements: Node 20+, a JDK, the Clojure CLI and Chrome. From this directory:

```sh
npm install          # also builds packages/polku, which the app links
npm run dev          # backend + nREPL, shadow-cljs, Tailwind, polku server
```

The app opens at http://localhost:3000. It uses the same ports as the demo and the other examples (3000 and 4777), so run one at a time. Saved games live in the browser's localStorage.

## Run it on another machine

The browser talks to three ports on the machine that runs `npm run dev`: 3000 (the app), 9630 (shadow-cljs live reload) and 4777 (the Polku server). The widget and live reload connect to the host and protocol the page was loaded from.

Recording the screen and microphone needs a secure context: `https://`, or `http://localhost`. A page opened from `http://<server-ip>:3000` can show the app and live reload, but the browser refuses to record.

**With Tailscale (recommended).** With HTTPS certificates enabled for the tailnet, `tailscale serve` gives the server an HTTPS name and forwards to the loopback ports, so nothing listens on the network and the page is a secure context:

```sh
bin/tailnet-up       # forwards 443 → 3000, 4777 → 4777 and 9630 → 9630, then runs npm run dev
bin/tailnet-down     # removes the forwards
```

Open `https://<machine>.<tailnet>.ts.net`. shadow-cljs listens on localhost only, so it does not collide with the forward on port 9630.

**On the local network.** Open ports 3000, 9630 and 4777 in the firewall and start with

```sh
POLKU_HOST=0.0.0.0 POLKU_ORIGIN=http://<server>:3000 npm run dev
```

For recording, list `http://<server>:3000` under `chrome://flags/#unsafely-treat-insecure-origin-as-secure` in the browser you use. Anyone on the network can then send requests to the Polku server, which the agent acts on, so do this only on a network you trust.

## Keep changing it

Start the agent in a second terminal with `npm run agent`, press Record in the page and describe the next change. The [demo README](../../demo/README.md) explains recording in more detail.

This directory is a complete app of its own, so changes here do not affect the demo or the other examples. To discard your changes and return to the committed version, stop `npm run dev` and the agent, then run `bb reset` (needs [Babashka](https://babashka.org)).
