# Sandbox game example

This app is a side-scrolling 2D sandbox game with digging, caves, chests, a shop, slimes and a day and night cycle. It was built from requests recorded with the Polku widget, and a person can keep describing changes there for you to make. Run the `/polku` skill to start waiting for their requests.

The person watches the browser at http://localhost:3000. Every change has to reach that page through live reload, without restarting the dev processes.

## Stack

- **Backend:** Clojure with Integrant (system), Reitit (routing), Jetty (HTTP), next.jdbc and HugSQL (PostgreSQL).
- **Frontend:** ClojureScript compiled by shadow-cljs, UIx (React 19) for components, Tailwind CSS v4 for styling.
- **Database:** PostgreSQL in Docker on port 5433, database `demo`, user `demo`. `bin/db psql` opens a shell.

## Where things go

| What | Where |
| --- | --- |
| UI components | `src/cljs/demo/` (entry point `demo.app`) |
| API routes | `api-routes` in `src/clj/demo/handler.clj`, under `/api` |
| SQL queries | `resources/sql/queries.sql` (HugSQL); each becomes a function in `demo.db` |
| Schema changes | a new file `resources/migrations/NNN-description.sql`; never edit one that has been applied |
| System wiring | `src/clj/demo/system.clj` |

## Making changes appear

- **ClojureScript and CSS** reload on save. Write Tailwind classes as strings (`{:class "text-xl md:flex"}`) so the Tailwind scanner finds them. A compile error leaves the old page in place, so confirm the build after saving: `grep '\[cljs\]' .dev.log | tail -3` should end with `Build completed` and no warnings.
- **Clojure** needs a reload through the REPL after you save: `brepl -p $(cat .nrepl-port) "(user/reset)"`. It stops the system, reloads changed namespaces with clj-reload and starts the system again. A compile error is printed and leaves the system stopped; fix the file and run `(user/reset)` again.
- **SQL queries** are picked up by `(user/reset)` too.
- **Migrations** run on every system start, so `(user/reset)` applies new files. `(user/migrate)` applies them without a restart.

Check an API change with `curl -s localhost:3000/api/...` before reporting it done. Commands that name a path outside this directory as an argument, even `curl -o /dev/null`, stop for a permission prompt that the person in the browser cannot see, so keep files and output inside this directory. Redirections such as `2>/dev/null` are fine.

## Conventions

- The frontend calls the backend with `js/fetch` on same-origin `/api/...` paths and JSON bodies. Responses are encoded by Muuntaja; return plain maps from handlers.
- HugSQL results use unqualified kebab-case keys.
- Keep the Polku `init` call in `demo.app/init`. The widget lives outside `#app`, so the app's markup and styles do not affect it.

## Dev processes

`npm run dev` starts everything: PostgreSQL, the backend with nREPL (port in `.nrepl-port`), shadow-cljs, the Tailwind watcher and the Polku ingest server on port 4777. Their combined output is in `.dev.log`. If the page does not load, check whether it is running before changing code.
