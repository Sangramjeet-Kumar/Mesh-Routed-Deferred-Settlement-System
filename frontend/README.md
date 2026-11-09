# Mesh Settlement Lab frontend

React, TypeScript, Vite, TanStack Query, Lucide icons, and locally bundled
variable fonts. Spring Boot remains the only backend.
Font license notices are included in `public/licenses/` and the packaged app.

## Development

Start the backend from the repository root:

```powershell
.\mvnw.cmd spring-boot:run
```

Then, with Node 22.12+ (22.x), Node 24 LTS, or another compatible release:

```powershell
cd frontend
npm.cmd ci
npm.cmd run dev
```

Vite serves http://127.0.0.1:5173 and proxies `/api` to
http://127.0.0.1:8080. No API paths, headers, or response shapes are changed.
On macOS/Linux, use `npm` and `./mvnw` in place of the Windows commands.
Stop Vite before a Maven build: Maven installs dependencies in this same folder.

```powershell
npm.cmd test
npm.cmd run build
```

`build` performs TypeScript checking and creates `dist/`. For a standalone app,
run `.\mvnw.cmd clean verify` at the repository root. Maven installs an isolated,
pinned Node/npm toolchain under `target/frontend`, runs `npm ci`, builds and tests
the frontend, and packages `dist/` as static resources in the Spring Boot JAR.
Generated output and `node_modules` stay out of Git.

## Structure

- `src/api`: request/response types and the shared fetch client.
- `src/hooks/useDashboard.ts`: three-second polling, cancellation, single-action
  locking, mutation lifecycle, and the session activity feed.
- `src/components/ui`: common buttons and status badges.
- `src/components/dashboard`: composer, network visualization, balances, ledger,
  and activity stream.
- `src/pages`: dashboard composition and interaction tests.
- `src/styles`: design tokens, responsive layout, focus states, and reduced motion.

## Behavior boundaries

All actions call the existing endpoints. POSTs are never retried automatically.
After an action, GETs refresh the server state; an uncertain action result is
shown without assuming whether the server applied it. The last successful data
remains visible if polling fails, alongside an explicit connection warning.

The form keeps the existing numeric conversion and applies no new amount/scale
validation. Defaults remain Alice to Bob, INR 500, demo PIN 1234, TTL 5, and
`phone-alice`. Masking the PIN is a UI treatment; it does not add authentication.

The ledger renders its own status, while activity renders the upload response
verbatim. An upload response of SETTLED does not override a REJECTED ledger row.
Cache claims are not labelled as successful settlements. See
[the Phase 1 review](../docs/phase-1-review.md) for the deferred backend fixes.

Device counts and packet IDs come from `/api/mesh/state`. Diagram lines indicate
possible connections and shared packets, not measured hops or packet routes.
The API exposes eight-character packet-ID previews, so the unique packet count
in the header is based on those previews. Unrecognized devices remain available
in the device inspector but do not receive invented positions in the diagram.

Rounds, activity, and the last injected packet are session-local; reloading clears
them. The server's mesh, balances, and ledger remain authoritative. Mesh reset
clears packets and cache while retaining balances and ledger. Ledger filtering
is local to the latest 20 records returned by the backend.
