# Bridge authentication and observability

## Implemented scope

- External `/api/bridge/` requests require one `X-Bridge-Api-Key` matching the
  environment-configured `BRIDGE_API_KEY`. Blank configuration stops startup.
- Incorrect, missing, blank, or repeated key headers receive HTTP 401 before
  parsing, claiming, decrypting, or settling. The same generic reason is returned
  for key failures; credentials and request bodies are never logged by the filter.
- Key digests have fixed length and use `MessageDigest.isEqual`. This is an HTTP
  authentication implementation, separate from the unchanged ciphertext hash.
- Actuator exposes only minimal health and key-protected metrics. No environment,
  configuration, heap dump, or mutable management endpoint is enabled/exposed.
- HTTP requests get a server-generated `X-Request-Id`. Logging context is restored
  after completion. DEBUG completion logs contain method/status/duration only.
- A separate AOP observer records ingestion response counts/durations from both
  external and simulator calls. Bounded outcomes are the only ingestion labels;
  no hashes, bridge IDs, VPAs, amounts, secrets, or exception messages are tags.
- Gauges expose claim count, device count, and stored packet copies. Standard
  Actuator meters provide HTTP, JVM, and datasource-pool diagnostics.

## Key setup

```powershell
.\scripts\create-bridge-key.ps1
. .\scripts\load-database-env.ps1
.\mvnw.cmd spring-boot:run
```

The first script creates an ignored `.local/bridge.psd1` with a random 256-bit
key encoded as 64 hexadecimal characters. Existing keys are preserved. The loader
reads database settings and the bridge key into the current terminal without
printing credentials. Other environments can supply `BRIDGE_API_KEY` directly.
Real keys must never be committed or bundled into the React app.

## Behavior preserved

Payment services, encryption, the Base64-text hash, freshness checks, claim-on-
failure rules, monetary inputs, JPA entities, and transaction boundaries are
unchanged. Authorized requests retain existing response shapes/outcomes/statuses.
Original HTTP characterization tests now supply a test-only key; assertions about
payment behavior were retained. PostgreSQL tests continue to use a dedicated
database/schema and do not depend on application credentials.

The React simulator still calls the ingestion service directly. It needs no key
and its API paths remain unchanged. `mesh.ingestion{outcome=SETTLED}` counts the
legacy response, including insufficient-funds REJECTED ledger rows; it does not
claim that funds moved successfully. Claim gauges also include failed attempts.

## Validation

`verify -Ppostgres-it` passes all 88 cases: 66 Java unit/characterization cases,
14 frontend cases, and 8 PostgreSQL integration cases. This includes 15 focused
filter cases and 5 additional HTTP/metrics characterization cases. TypeScript,
Vite production packaging, and the executable JAR build pass. Existing payment
assertions retain their original expected outcomes.

Packaged-JAR HTTP checks against PostgreSQL also pass: unauthenticated bridge,
metrics, and matrix-parameter requests return 401; an authorized malformed packet
retains HTTP 200 / INVALID; the correlation header and ingestion/device/claim
meters are present. Health returns only `status: UP`, and `/actuator/env` is 404.
The runtime smoke checks did not change the ledger or balances. Their one failed
decryption claim was process-local and removed by stopping the temporary server.

## Limits and next steps

This is a shared server secret, not mutual TLS, per-device identity, or account-
holder authentication. `X-Bridge-Node-Id` remains untrusted metadata. Use HTTPS
outside a local demo and restrict access to unauthenticated simulator controls.
The management endpoints currently share the application listener. Key rotation
requires server/client coordination and an application restart. Metrics reset
with the process; no external metrics backend/exporter is installed. Correlation
is scoped to HTTP threads and does not automatically reach parallel mesh workers.

Deferred settlement behavior fixes and distributed Redis idempotency require
separate approval. They were not included in this phase.
