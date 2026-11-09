# React migration

## Result

The Thymeleaf dashboard has been replaced by a React + TypeScript single-page
dashboard in `frontend/`. The application still serves at `/` through Spring
Boot. Vite handles development and bundling; TanStack Query coordinates reads
and actions. Fonts and icons are bundled locally.

The interface includes payment composition, a mesh diagram driven by server
state, device packet inspection, gossip and upload controls, balance cards,
ledger filtering, a session activity feed, loading/error states, request locking,
keyboard focus, reduced-motion support, and responsive layouts. On small screens
the topology can scroll horizontally to keep node labels readable.

The three-second polling interval is retained. Polling intervals pause during
actions; pending reads are cancelled before POSTs, and reads refresh afterwards.
No POST retries or optimistic balance updates are introduced. Failed requests
produce explicit feedback, and previous data remains visible during read errors.

## Backend boundary

Production Java services, cryptography, models, repositories, API controllers,
and application properties are unchanged in this phase. The sole deleted Java
class is DashboardController: Spring's static welcome-page handling now serves
the React entry point. The Thymeleaf dependency and dashboard template are removed.

Maven installs a pinned local Node/npm toolchain, installs the lockfile with
`npm ci`, checks TypeScript, builds/tests React, and packages `frontend/dist` into
the executable JAR. The application does not require Node at runtime.

Only the UI delivery assertions in the HTTP characterization test changed:
`/` now forwards to `index.html`, and that page must contain React's root element.
All payment, idempotency, concurrency, and failure assertions remain intact.

## Validation

- All 46 Java characterization cases pass after Thymeleaf removal.
- All 14 frontend cases pass, covering the demo flow, API payloads, zero/negative/
  extra-decimal amounts, conflicting upload/ledger statuses, repeated-click
  prevention, failed POSTs without retry, connection recovery, local filtering,
  and safe rendering of bridge metadata.
- TypeScript checking and the Vite production build pass.
- Maven `verify` packages the frontend and backend together successfully.
- A clean Maven `clean verify` also passes all 60 cases. HTTP checks of the final
  packaged entry page, CSS, and local font return 200; no external font request
  remains. Vite starts successfully and its `/api` proxy returns the same account
  data as Spring Boot and the expected five mesh devices.
- The packaged JAR was opened in Brave and the desktop inject → gossip → upload
  flow was exercised: Alice changed from INR 5000 to 4500, Bob from 1000 to 1500,
  and the ledger showed one INR 500 settlement. The mesh showed all five devices
  carrying the packet after gossip.

Browser control was stopped by the user with Escape after that flow. Further
browser interaction was stopped. Mobile visual verification and a browser
recheck of the subsequent larger typography/local fonts remain uncompleted.
Automated component tests and build checks cover the final source.

## Retained risks

All [Phase 1 backend limitations](phase-1-review.md) remain intentionally
unchanged, including SETTLED/REJECTED disagreement, claim retention after failure,
Base64-text hash identity, amount rounding, unverified PINs, and ephemeral state.
This phase adds no authentication or financial guarantees.

The mesh API supplies shortened packet IDs, so the UI's unique packet count is
an estimate based on those previews. Diagram edges describe possible connections
and shared packets rather than tracked routes. Activity, rounds, and the latest
packet receipt are local to a browser session. Ledger filters cover only the
latest 20 records. See [frontend/README.md](../frontend/README.md) for details.

PostgreSQL, authentication/observability changes, and Redis have not been started.
