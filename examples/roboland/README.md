# Roboland example

A blank frontend-only app to build through Polku. There is no backend: shadow-cljs's built-in dev server serves the page.

## Run it

Requirements: Node 20+, a JDK and Chrome. From this directory:

```sh
npm install          # also builds packages/polku, which the app links
npm run dev          # shadow-cljs (with its dev server), Tailwind, polku server
```

The app opens at http://localhost:3100.

## Ports

Roboland uses its own ports so it can run next to [Mine Valley](../terraria-clone), which uses 3000, 9630 and 4777:

| Port | What |
| --- | --- |
| 3100 | the app (shadow-cljs `:dev-http`) |
| 9730 | shadow-cljs server: live reload websocket and the shadow-cljs UI |
| 9731 | shadow-cljs nREPL |
| 4877 | Polku ingest server |

All of them listen on localhost only.

## Run it next to Mine Valley

Polku's widget saves by committing every change in the repository and can switch the checkout to another branch from its History panel. Two examples driven from the same checkout would commit and switch each other's files, so run Roboland from a clone of its own:

```sh
git clone <this repository> ~/projects/polku-roboland
cd ~/projects/polku-roboland/examples/roboland
npm install
npm run dev
```

Start its agent in the same directory with `npm run agent`. Each checkout has its own `.polku` workspace, git index and branch.

## Behind a reverse proxy on one HTTPS name

When the proxy forwards `/polku/*` to port 4877 and `/shadow/*` to port 9730 (removing the prefix), and everything else to port 3100, start with

```sh
POLKU_ENDPOINT=/polku SHADOW_DEVTOOLS_URL=https://<host>/shadow POLKU_ORIGIN=https://<host> npm run dev
```

With Caddy:

```caddy
@roboland host roboland.<domain>
handle @roboland {
	handle_path /polku/* {
		reverse_proxy localhost:4877
	}
	handle_path /shadow/* {
		reverse_proxy localhost:9730
	}
	handle {
		reverse_proxy localhost:3100
	}
}
```

`POLKU_ENDPOINT` sets the address the widget sends to, and bin/shadow-watch passes `SHADOW_DEVTOOLS_URL` to shadow-cljs as the live reload address. Without `POLKU_ORIGIN`, the Polku server answers the widget's requests with 403. All three are read when `npm run dev` starts, so restart it to change them.

## Keep changing it

Start the agent in a second terminal with `npm run agent`, press Record in the page and describe the next change. The [demo README](../../demo/README.md) explains recording in more detail.

To discard your changes and return to the committed version, stop `npm run dev` and the agent, then run `bb reset` (needs [Babashka](https://babashka.org)).
