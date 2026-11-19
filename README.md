# Mesh-Routed Deferred Settlement System

A Spring Boot application and React dashboard demonstrating encrypted payment
instructions travelling through a simulated offline mesh, then settling when an
internet-connected bridge delivers them to the backend.

This is a deferred-settlement demo. Packet injection does not move money or
reserve funds. There is no real Bluetooth transport, bank integration, or UPI
network connection.

## Contents

- [Current implementation](#current-implementation)
- [Run and develop](#run-and-develop)
- [Demo flow](#demo-flow)
- [Architecture](#architecture)
- [Core engineering](#core-engineering)
- [Project structure](#project-structure)
- [API contracts](#api-contracts)
- [Bridge authentication and observability](#bridge-authentication-and-observability)
- [Tests and verification](#tests-and-verification)
- [Known limitations](#known-limitations)
- [Next phases](#next-phases)

## Current implementation

| Layer | Implementation |
| --- | --- |
| Backend | Java 17 target, Spring Boot 3.3.5, Spring MVC, Spring Data JPA |
| Frontend | React, TypeScript, Vite, TanStack Query, locally bundled fonts/icons |
| Database | PostgreSQL; Flyway migrations and Hibernate schema validation |
| Encryption | RSA-2048 OAEP with SHA-256/MGF1-SHA-256, AES-256-GCM |
| Idempotency | Shared Redis atomic claims with TTL; PostgreSQL unique packet hash |
| Mesh | Five simulated devices: four offline phones and one connected bridge |
| Packaging | One executable Spring Boot JAR containing the React production build |

React, PostgreSQL, bridge API-key authentication, and baseline observability are
implemented, together with Redis idempotency. H2 remains test-only. Known payment/API behaviors
remain unchanged; external ingest and metrics now require a bridge API key.

## Run and develop

### Bundled application

Install JDK 17 or newer and set `JAVA_HOME` or add Java to `PATH`. Maven downloads
its own Maven/Node/npm tooling; a global Node installation is not required for
the bundled application. The first build needs internet access.

Redis must also be running. If you already created `upimesh-redis` using the
learning commands, reuse it:

```powershell
docker start upimesh-redis
docker exec upimesh-redis redis-cli ping
```

For a fresh installation, `docker compose up -d redis` uses the pinned image,
loopback port 6379, persistent storage, AOF, and a no-eviction policy. Do not run
both setups on the same port. Compose does not adopt the manually created
container. Stop the manual container first if you choose Compose; its existing
data volume will not automatically be imported into the Compose volume.

Defaults connect to `127.0.0.1:6379`, database 0, namespace `upimesh:demo`.
Optional `REDIS_HOST`, `REDIS_PORT`, `REDIS_DATABASE`, `REDIS_NAMESPACE`,
`REDIS_USERNAME`, and `REDIS_PASSWORD` settings are listed in `.env.example`.
All application instances processing the same packets must use the same Redis
database and namespace. Credentials and TLS/network restrictions are required
for a secured deployment; the provided local setup binds only to loopback.

PostgreSQL must be running with a dedicated application database and login role.
Set `DB_URL` (default `jdbc:postgresql://127.0.0.1:5432/upimesh`), `DB_USERNAME`
(default `upimesh`), and the required `DB_PASSWORD` environment variable. Use the
application role, not the administrator role. See `.env.example` for variable
names; `.env` files are not loaded automatically.

On this checkout, dedicated `upimesh` and `upimesh_test` databases and roles were
provisioned locally. Their generated credentials are in the Git-ignored
`.local/database.psd1`. Load them in each new PowerShell terminal:

```powershell
.\scripts\create-bridge-key.ps1  # Once per checkout; existing keys are preserved.
. .\scripts\load-database-env.ps1
```

That helper sets environment variables for the current terminal without printing
credentials. New checkouts must provision their own database/role and set their
environment variables; local credentials are intentionally not committed.
The helper also loads the key from ignored `.local/bridge.psd1`. Alternatively,
set `BRIDGE_API_KEY` yourself. A missing or blank key prevents application startup.
Never put this key in React source or a `VITE_*` variable.

For a new installation, an administrator can provision the application database
in an interactive `psql` session (the password command prompts securely):

```text
CREATE ROLE upimesh LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE;
\password upimesh
CREATE DATABASE upimesh OWNER upimesh;
```

For integration tests, provision a separate `upimesh_test` role/database the same
way. Never point the test suite at the application's database.

PowerShell, from the repository root:

```powershell
.\mvnw.cmd spring-boot:run
```

macOS/Linux:

```bash
./mvnw spring-boot:run
```

Open **http://localhost:8080**. Stop with `Ctrl+C`.

### React development

Keep Spring Boot running on port 8080. With a compatible Node installation
(Node 22.12+ on the 22.x line, or a supported newer LTS), start a second terminal:

```powershell
cd frontend
npm.cmd ci
npm.cmd run dev
```

Open **http://127.0.0.1:5173**. Vite proxies `/api` to Spring Boot. On macOS/Linux,
use `npm` instead of `npm.cmd`. Stop Vite before running Maven: Maven installs
frontend dependencies in the same directory.

### Build and run the executable JAR

```powershell
. .\scripts\load-database-env.ps1  # Local convenience; or set DB_* yourself.
.\mvnw.cmd clean verify
java -jar target/upi-offline-mesh-0.0.1-SNAPSHOT.jar
```

Maven checks TypeScript, builds and tests React, runs the Java tests, and bundles
the frontend into the JAR. Node is not needed at application runtime. Generated
assets and dependencies are excluded from Git. See [frontend/README.md](frontend/README.md).

### Current database and reset behavior

Flyway creates the schema before JPA starts; Hibernate validates the mappings
instead of recreating tables. Migrations live in `db/migration/postgresql`.
Automatic schema deletion is disabled. The application no longer contains H2
at runtime, and `/h2-console` is unavailable.

When the account table is empty, the existing demo seeder creates Alice INR 5000,
Bob INR 1000, Carol INR 2500, and Dave INR 500. Otherwise it leaves accounts alone.
Balances and ledger rows survive application restarts. Redis retains unexpired
claims independently of application processes. Mesh packets and RSA keys still
reset; old encrypted packets may become undecryptable.
**Reset mesh & cache** still clears only packets and claims, retaining ledger and
balances. No automatic H2 data import is performed.

## Demo flow

1. **Compose:** choose sender, receiver, amount, and demo PIN; click **Encrypt &
   inject**. `DemoService` creates a payment instruction with a UUID nonce and
   timestamp, encrypts it, and injects a TTL-5 packet at `phone-alice`.
2. **Gossip:** click **Run gossip round**. All devices are in range in this
   simulator. One round copies the packet to the other four devices, decrementing
   TTL on each new copy. Further rounds leave already-held copies unchanged.
3. **Upload:** click **Upload to backend**. The bridge's held packets are delivered
   to `BridgeIngestionService` in parallel. The simulator calls that service
   directly; external bridges can POST to `/api/bridge/ingest`.
4. **Inspect:** view account balances, ledger status, packet counts, and the
   activity stream. Upload responses and ledger statuses are displayed separately.
5. **Repeat upload:** click **Upload to backend** again. Packets remain at the
   bridge, so the same encoded ciphertext is uploaded again and ordinarily returns
   `DUPLICATE_DROPPED` while its claim exists.

The default mesh has one bridge. The concurrency tests exercise simultaneous
uploads of the same packet from multiple bridges. Diagram edges indicate possible
connections and shared packets, not recorded routes. Rounds and activity are local
to the browser session; mesh and financial state come from the backend.

## Architecture

```
┌─────────────────────────────────────────────────────────────────────────┐
│                         SENDER PHONE (offline)                          │
│  PaymentInstruction { sender, receiver, amount, pinHash, nonce, time }  │
│              │                                                          │
│              ▼ encrypt with server's RSA public key                     │
│   MeshPacket { packetId, ttl, createdAt, ciphertext }                   │
└──────────────────────────────────────┬──────────────────────────────────┘
                                       │ Bluetooth gossip
                                       ▼
        ┌─────────┐  hop   ┌─────────┐  hop   ┌─────────┐
        │stranger1│ ─────▶ │stranger2│ ─────▶ │ bridge  │ ◀── walks outside
        └─────────┘        └─────────┘        └────┬────┘     gets 4G
                                                   │
                                                   ▼ HTTPS POST
┌─────────────────────────────────────────────────────────────────────────┐
│                     SPRING BOOT BACKEND (this project)                  │
│                                                                         │
│  /api/bridge/ingest                                                     │
│       │                                                                 │
│       ▼                                                                 │
│  [0] HttpBoundaryFilter: verify X-Bridge-Api-Key                         │
│       │       Missing/invalid key → HTTP 401, no ingestion work           │
│       ▼                                                                 │
│  [1] hash ciphertext (SHA-256)                                          │
│       │                                                                 │
│       ▼                                                                 │
│  [2] RedisIdempotencyService.claim(hash) ◀── atomic SET NX + expiry     │
│       │       Shared across instances; duplicates stop here.           │
│       │       No confirmed claim → no decryption or settlement.         │
│       ▼                                                                 │
│  [3] HybridCryptoService.decrypt(ciphertext)                            │
│       │       (RSA-OAEP unwraps AES key, AES-GCM decrypts payload       │
│       │        AND verifies the auth tag — tampering = exception)       │
│       ▼                                                                 │
│  [4] Freshness check: signedAt within last 24h                          │
│       │                                                                 │
│       ▼                                                                 │
│  [5] SettlementService.settle()                                         │
│       @Transactional: debit sender, credit receiver, write ledger       │
│       @Version on Account = optimistic locking (defense in depth)       │
└─────────────────────────────────────────────────────────────────────────┘
```

The diagram shows the conceptual phone-to-server path. BLE is simulated, the
local server uses HTTP, and TLS termination is a deployment concern. The React
UI controls the simulator through the existing `/api` endpoints. Its read queries
poll every three seconds and refresh after actions; POST actions are not retried
automatically.

## Core engineering

### 1. Hybrid encryption across untrusted relays

`PaymentInstruction` contains sender/receiver VPAs, amount, PIN hash, nonce, and
`signedAt`. The outer `MeshPacket` contains readable `packetId`, `ttl`, `createdAt`,
and an opaque Base64-encoded encrypted envelope.

RSA-OAEP encrypts only small payloads, so the application uses hybrid encryption:

1. Generate a fresh AES-256 key and random 12-byte IV for each encryption.
2. Encrypt the instruction JSON with AES-GCM and a 128-bit authentication tag.
3. Wrap the AES key with the server's RSA-2048 public key, explicitly selecting
   SHA-256 for both OAEP and MGF1.
4. Encode the combined binary envelope as Base64:

```text
[256-byte RSA-wrapped AES key][12-byte IV][AES ciphertext + 16-byte GCM tag]
```

The server unwraps the key, verifies/decrypts GCM, and deserializes the instruction.
Wrapped-key, IV, payload, or tag modification causes decryption to fail. Relays
can alter outer routing metadata, so the backend does not use `packetId` or
`createdAt` for payment identity or freshness.

Encryption protects confidentiality and integrity of the encrypted instruction;
it does not authenticate the account holder. `signedAt` is not a digital
signature, and the demo does not verify `pinHash`. See `HybridCryptoService` and
`ServerKeyHolder`.

### 2. Ciphertext identity and concurrent duplicate delivery

The backend currently hashes the **Base64 ciphertext text**, using SHA-256. It
claims that hash before doing RSA decryption:

```java
Boolean claimed = redis.opsForValue().setIfAbsent(key, "claimed", ttl);
if (claimed == null) throw new IllegalStateException("Unconfirmed Redis claim");
return claimed;
```

The write maps to Redis `SET ... NX EX/PX ...`: the condition and expiry are one
atomic command. One caller wins across all instances sharing the store; others
for the same hash return `DUPLICATE_DROPPED`. Changing the outer packet ID does not
change this hash. Hashing before decryption avoids repeated RSA work for duplicate
encoded packets.

The transaction table also enforces uniqueness of `packet_hash`. Together with
transactional settlement, this protects against multiple committed settlements
for the same hash. It is not a guarantee of eventual successful settlement after
failure, across restarts, or for every representation of the same encrypted bytes.

Keys are `mesh:{<namespace>}:claim:<unchanged-ciphertext-hash>`. Redis enforces the
configured 24-hour TTL without a Java eviction scheduler. Unlike the old map,
expired claims become available promptly rather than waiting for minute-based
cleanup. Failed processing still retains its acquired claim until expiry/reset.

Redis errors and unconfirmed replies propagate to ingestion's existing catch:
HTTP ingestion returns legacy `INVALID` / `internal_error`, with no settlement.
An outage is never interpreted as a duplicate and there is no local fallback or
automatic application-level claim retry. A timeout can leave an uncertain claim
in Redis; it is not deleted to force a retry. PostgreSQL uniqueness remains the
durable guard because Redis and PostgreSQL do not share one atomic transaction.

`size()` and reset use namespaced SCAN, not `KEYS` or database-wide flushing.
The count is a diagnostic snapshot, deduplicated against repeated scan results;
expiry and concurrent uploads can still change it. Reset deletes only this exact
namespace in batches and is not atomic against active uploads. It affects every
instance sharing that namespace. Use demo reset only while uploads are idle.

The Docker configuration persists AOF data on a volume. With `appendfsync everysec`,
a crash can lose roughly the latest second of writes. Keep eviction disabled and
do not treat Redis persistence as an unlimited exactly-once guarantee. See the
[Redis migration review](docs/redis-migration.md) for failure and lifecycle limits.

### 3. Freshness and replay protection

After decryption, ingestion checks the protected `signedAt` timestamp. It rejects
instructions older than the configured 24-hour window and those more than roughly
five minutes in the future; age comparisons truncate to whole seconds.

Repeating an identical encoded packet is caught by its hash while claimed. A
settled hash also remains unique in the current database. A fresh nonce helps
identify a distinct instruction, but nonce reuse is not independently checked.
Encryption itself is randomized, so re-encrypting the same instruction produces
a different ciphertext and hash. Alternate accepted Base64 encodings also have
different text hashes; this known replay gap is retained pending approval.

### 4. Atomic financial updates and account concurrency

`SettlementService.settle()` is a Spring-managed `@Transactional` operation:

```text
load sender + receiver
        ↓
require positive amount and check sender balance
        ↓
sufficient funds → debit sender + credit receiver + SETTLED ledger row
insufficient funds → REJECTED ledger row; balances unchanged
        ↓
commit together, or roll back on failure
```

Money uses `BigDecimal` with database precision 19 and scale 2. Each `Account`
has an `@Version` field for optimistic locking. Distinct concurrent payments can
conflict on those versions; a failed transaction rolls back rather than losing
an update. There is no automatic retry policy. Cache claims are outside the
financial database transaction.

The insufficient-funds ledger status is `REJECTED`, while ingestion currently
returns `SETTLED` for that recorded outcome. The UI shows both values independently;
this migration does not correct that API inconsistency.

## Project structure

```text
frontend/
  src/api/                 Typed API client and transport types
  src/components/ui/       Shared controls and status badges
  src/components/dashboard/ Composer, mesh, balances, ledger, activity
  src/hooks/               Polling, action locking, activity state
  src/pages/               Dashboard composition and interaction tests
  src/styles/              Responsive styling and accessibility states
  public/licenses/         Bundled font notices
src/main/java/com/demo/upimesh/
  controller/              REST endpoints in ApiController
  service/                 Demo creation, mesh, idempotency, ingestion, settlement
  crypto/                  RSA key lifecycle and hybrid encryption
  model/                   JPA entities, repositories, packet/instruction models
  config/                  HTTP authentication, correlation, and metric binding
src/main/resources/
  application.properties   PostgreSQL, server, logging, and freshness settings
  db/migration/postgresql/ Versioned PostgreSQL schema migrations
src/test/java/com/demo/upimesh/
                           Java characterization and concurrency tests
docs/                      Phase reviews, limitations, and migration plans
pom.xml                    Combined frontend/backend build
```

## API contracts

| Method | Path | Behavior |
| --- | --- | --- |
| GET | `/` | React dashboard |
| GET | `/api/server-key` | Base64 public key and algorithm metadata |
| GET | `/api/accounts` | Accounts and current balances |
| GET | `/api/transactions` | Latest 20 ledger entries, descending ID |
| GET | `/api/mesh/state` | Device state, shortened packet IDs, cache size |
| POST | `/api/demo/send` | Encrypt and inject a demo instruction |
| POST | `/api/mesh/gossip` | Run one gossip round |
| POST | `/api/mesh/flush` | Deliver packets held by connected bridges |
| POST | `/api/mesh/reset` | Clear mesh and cache; retain balances/ledger |
| POST | `/api/bridge/ingest` | API-key-protected external ingestion |
| GET | `/actuator/health` | Public status only, without component details |
| GET | `/actuator/metrics` | API-key-protected metric names |
| GET | `/actuator/metrics/{name}` | API-key-protected metric measurements |

Example bridge request:

```http
POST /api/bridge/ingest
Content-Type: application/json
X-Bridge-Api-Key: <your-bridge-api-key>
X-Bridge-Node-Id: phone-bridge-42
X-Hop-Count: 3

{
  "packetId": "550e8400-e29b-41d4-a716-446655440000",
  "ttl": 2,
  "createdAt": 1730000000000,
  "ciphertext": "base64-encoded-encrypted-envelope"
}
```

The outer `createdAt` above is illustrative; freshness uses the timestamp inside
the encrypted instruction. Bridge headers default to `unknown` and `0`.

```json
{
  "outcome": "SETTLED",
  "packetHash": "sha256-hex-string",
  "reason": null,
  "transactionId": 42
}
```

Ingestion returns `SETTLED`, `DUPLICATE_DROPPED`, or `INVALID`. Service-returned
outcomes use HTTP 200; malformed HTTP input can still fail before ingestion.
Duplicate results have no transaction ID. Invalid reasons include
`decryption_failed`, `stale_packet`, `future_dated`, and legacy `internal_error`
messages. Mesh-flush results substitute an empty reason and transaction ID `-1`
when those fields are absent. Those contracts are unchanged.

## Bridge authentication and observability

External requests under `/api/bridge/` require exactly one `X-Bridge-Api-Key`
header matching `BRIDGE_API_KEY`. Missing, incorrect, blank, or repeated headers
return HTTP 401 with `reason: invalid_or_missing_api_key` before body parsing,
decryption, or hash claiming. Keys are compared via fixed-length SHA-256 digests
and `MessageDigest.isEqual`; credentials are not logged. This authentication hash
is separate from the unchanged payment ciphertext-hash calculation.

Authorized ingestion retains the existing HTTP 200 and outcome semantics.
The key is not accepted through a query string. `X-Bridge-Node-Id` remains
caller-supplied metadata, not an authenticated device identity. Use HTTPS when
bridges connect across a network; local HTTP does not protect a transmitted key.
Key rotation currently requires changing the server secret and restarting it.

The simulator's `/api/mesh/flush` calls ingestion directly, so the React demo does
not need the key. Demo controls remain unauthenticated; this phase does not make
the entire demo a production-secure payment service. Restrict access to the demo
deployment separately. Authentication does not verify the sender or PIN.

Only Actuator `health` and `metrics` are enabled/exposed. Health reveals status
without database/component details. Metrics use the same bridge key. Environment,
configuration, heap dump, and other Actuator endpoints are not exposed.

| Meter | Meaning |
| --- | --- |
| `mesh.ingestion` | Response count and duration, tagged by bounded `outcome` |
| `mesh.http.authentication` | Accepted/rejected key checks for bridge or metrics |
| `mesh.idempotency.claims` | Shared Redis claim-count snapshot, including failed attempts |
| `mesh.devices` | Simulated device count |
| `mesh.packet.copies` | Total copies held across simulated devices |
| `http.server.requests`, JVM/pool meters | Standard Actuator HTTP/runtime diagnostics |

`mesh.ingestion` observes external and simulator calls. Its `SETTLED` label means
the legacy ingestion response, not necessarily a SETTLED ledger row. Meters never
use hashes, VPAs, amounts, bridge IDs, credentials, or error text as labels.
Counters/timers are process-local and reset on restart; the claim gauge reads the
shared Redis store. No external exporter is installed. Namespace scans are suitable
for this demo's size, not an O(1) production counting strategy.

API/Actuator requests receive a server-generated `X-Request-Id`, also used in the
HTTP thread's logging context. Client request IDs are not trusted. The previous
logging context is restored after completion. Request-completion logs are DEBUG
and omit headers, bodies, query strings, and raw paths. Correlation is not
automatically propagated to parallel simulator worker threads.

## Tests and verification

```powershell
.\mvnw.cmd test
.\mvnw.cmd clean verify
```

The default suite contains **67 Java cases and 14 frontend cases (81 total)**:

- Java: simultaneous duplicate delivery, unique ledger persistence, both account
  balances, crypto compatibility/tampering, freshness, malformed input, cache
  behavior, rollback, optimistic-lock conflicts, and the HTTP demo flow.
- React: request payloads, workflow interactions, independent response/ledger
  statuses, amount compatibility, duplicate-click locking, error recovery,
  filtering, and safe rendering of API text.
- HTTP boundary: missing/invalid/repeated keys, matrix-parameter paths, preserved
  request bodies, fail-closed configuration, correlation cleanup, and protected
  metrics/minimal health. Original authorized payment assertions are retained.

From `frontend/`, `npm.cmd test` runs frontend tests and `npm.cmd run build`
checks TypeScript and builds assets. Test-only state is isolated from a running
application's database. Characterization tests intentionally preserve known bugs.

The packaged desktop demo and Vite proxy were previously verified. Mobile visual
verification remains incomplete after browser control was stopped. See the
[React migration review](docs/react-migration.md) for verification details.

### PostgreSQL integration tests

```powershell
. .\scripts\load-database-env.ps1
.\mvnw.cmd verify -Ppostgres-it
```

Set `PG_TEST_URL`, `PG_TEST_USERNAME`, and `PG_TEST_PASSWORD` if not using the local
helper. The target database name must end in `_test` and its URL must differ from
`DB_URL`. The role must be able to create schemas in that dedicated database.
The suite creates a random `mesh_it_*` schema and drops only that schema when
finished. It fails if required connection settings are missing.

All **8 PostgreSQL integration cases** pass on the local PostgreSQL 18 service,
in addition to the 81 default cases, for **89 passing cases**. They cover migration/validation, concurrent
duplicate delivery, database uniqueness, rollback and claim retention, rejected
ledger/SETTLED response compatibility, numeric rounding, optimistic conflicts,
and balance/ledger persistence across application-context restarts. The packaged
JAR also serves the React page and account/mesh APIs successfully with PostgreSQL.

### Redis and PostgreSQL integration tests

With Docker running and the same dedicated `PG_TEST_*` settings loaded:

```powershell
. .\scripts\load-database-env.ps1
.\mvnw.cmd verify -Predis-it
```

This profile runs **23 integration cases**, including the 8 PostgreSQL cases,
for **104 passing cases with the default suites**. Do not combine it with
`-Ppostgres-it`. Redis tests create their own disposable containers and data
volumes; they never stop, reset, or flush your `upimesh-redis` container.

Coverage includes atomic claims across independent clients, exact TTL expiry,
namespace-scoped count/reset, Redis restart persistence, unavailable/unconfirmed
claims, retained claims after failure, and concurrent settlement across two
application contexts sharing Redis and a test-only RSA key. The PostgreSQL
settlement contract also runs with the real Redis implementation.

The packaged JAR was verified against the existing local Redis and PostgreSQL:
React served successfully, health was UP, malformed packets retained their
claims, duplicates remained blocked after a full JVM restart, and resetting an
isolated smoke-test namespace cleared its claims without changing account or
ledger data. See the [Redis migration review](docs/redis-migration.md).

## Known limitations

These remain deferred; documenting them does not change behavior:

- Insufficient funds records `REJECTED` but responds `SETTLED` through ingestion.
- Failed processing retains its claim, so retries may be dropped without a
  committed settlement. Cache-cleared replays can return a database constraint
  error rather than `DUPLICATE_DROPPED`.
- Equivalent Base64 encodings can bypass text-hash deduplication; nonces are not
  independently enforced and PIN hashes are not verified.
- Amount scale is not validated. H2 and PostgreSQL independently round a `0.005` transfer, which
  can increase combined balances. No new validation rules have been introduced.
- RSA keys and mesh packets remain process-local while PostgreSQL and configured
  Redis persistence survive application restarts. Multi-instance decryption also
  needs shared server keys; this phase does not change key management.
  External ingestion uses a shared key, but demo controls remain unauthenticated.
  The H2 console is disabled.
- Offline instructions do not prove or reserve available funds. Separate payment
  intents can overspend offline; actual balance checks occur only at settlement.
- Real BLE discovery, bank integration, rate limiting, durable key management,
  Redis failover/cluster operation, and comprehensive crash recovery remain
  outside the current demo. Redis claims are shared, not the entire application state.

See [the Phase 1 review](docs/phase-1-review.md) for detailed characterization.

## Next phases

PostgreSQL migration is complete; see the
[migration review](docs/postgresql-migration-plan.md). Remaining phases:

Bridge authentication and baseline observability are implemented; see the
[phase review](docs/authentication-observability.md).

1. **Explicitly approved behavior fixes:** the previously flagged outcome, retry,
   hashing, and monetary-validation changes remain deferred.
Redis, the last planned infrastructure phase, is implemented; see the
[Redis review](docs/redis-migration.md). The deferred behavior fixes above and
remaining production-hardening work require separate approval.

Future changes will update this README alongside their implementation. The
engineering flow, diagrams, guarantees, and limitations will remain documented.

## Troubleshooting

- **PowerShell cannot find `mvnw.cmd`:** use `.\mvnw.cmd` from the repository root.
- **Java cannot be found:** check `java -version`, `JAVA_HOME`, and `PATH`.
- **Port conflict:** change `server.port`; update the Vite proxy if the backend
  moves from port 8080.
- **Slow first build:** Maven is downloading its toolchain and dependencies.
- **Frontend dependency conflicts during development:** stop Vite before Maven
  runs its clean dependency installation.
- **Expected error logs during tests:** rejection/rollback tests deliberately
  exercise failures. Check the test summary instead of treating every log as a
  failed test. Investigate intermittent failures rather than assuming they pass
  after retries.
- **Missing DB_PASSWORD or connection failure:** load local settings in the same
  terminal used to start Java/Maven, verify PostgreSQL is running, and check the
  database/role. Do not change authentication rules to bypass a failed login.
- **Flyway checksum or schema validation error:** inspect the migration history
  and schema. Do not edit an applied migration or enable `create-drop` to hide it;
  add a new migration for approved schema changes.
- **Missing BRIDGE_API_KEY:** run `scripts/create-bridge-key.ps1`, then reload
  local settings in the launch terminal, or set the environment variable yourself.
- **HTTP 401 on bridge or metrics:** supply exactly one correct key header. A
  successful key check does not guarantee settlement; inspect the returned outcome
  and ledger separately.
- **Redis unavailable:** check `docker exec upimesh-redis redis-cli ping` (or
  `docker compose exec redis redis-cli ping`). Health reports DOWN. Claims stop
  ingestion; mesh-state/reset requests can also fail because they access Redis.
  Restore the connection instead of clearing claims or enabling a local fallback.
