# Roboland

Roboland is a blank frontend-only app so far. A person describes changes with the Polku widget, and you make them. Run the `/polku` skill to start waiting for their requests.

The person watches the browser at http://localhost:3100 (or through a reverse proxy). Every change has to reach that page through live reload, without restarting the dev processes.

## Stack

- **No backend.** shadow-cljs's built-in dev server serves `resources/public` on port 3100. Anything that needs to persist goes in the browser's localStorage.
- **Frontend:** ClojureScript compiled by shadow-cljs, UIx (React 19) for components, Tailwind CSS v4 for styling.

## Where things go

| What | Where |
| --- | --- |
| UI components | `src/cljs/roboland/` (entry point `roboland.app`) |
| Static files (images, audio) | `resources/public/` |
| CSS entry point | `src/css/app.css` |

## Making changes appear

ClojureScript and CSS reload on save. Write Tailwind classes as strings (`{:class "text-xl md:flex"}`) so the Tailwind scanner finds them. A compile error leaves the old page in place, so confirm the build after saving: `grep '\[cljs\]' .dev.log | tail -3` should end with `Build completed` and no warnings.

Commands that name a path outside this directory as an argument, even `curl -o /dev/null`, stop for a permission prompt that the person in the browser cannot see, so keep files and output inside this directory. Redirections such as `2>/dev/null` are fine.

## Conventions

- Keep the Polku `init` call in `roboland.app/init`. The widget lives outside `#app`, so the app's markup and styles do not affect it.

## Dev processes and ports

`npm run dev` starts shadow-cljs, the Tailwind watcher and the Polku ingest server. Their combined output is in `.dev.log`. If the page does not load, check whether it is running before changing code.

The ports differ from Polku's defaults so that Roboland runs next to Mine Valley (`examples/terraria-clone`):

| Port | What |
| --- | --- |
| 3100 | the app (shadow-cljs dev-http) |
| 9730 | shadow-cljs server: live reload and its UI |
| 9731 | shadow-cljs nREPL |
| 4877 | Polku ingest server (`curl -s localhost:4877/health`) |
