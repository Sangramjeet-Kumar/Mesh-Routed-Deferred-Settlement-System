# Phase 1: behavior-preserving review

## Validation

Run from PowerShell:

```powershell
.\mvnw.cmd test
```

The original three tests passed before changes. The expanded suite passes all
46 cases with no failures, errors, or skipped tests on the local Oracle JDK 25.
The project still compiles for Java 17; execution on a Java 17 runtime has not
been verified during this phase.

| Test class | Cases | Coverage |
| --- | ---: | --- |
| IdempotencyConcurrencyTest | 3 | Concurrent duplicate delivery, one committed ledger row, both balances, tampering, round-trip fields |
| BridgeIngestionCharacterizationTest | 8 | Freshness acceptance/rejection, claim retention, hash failure, optimistic-lock failure, diagnostic context |
| HybridCryptoCharacterizationTest | 7 | Explicit OAEP SHA-256/MGF1 SHA-256 interoperability, AES-256-GCM layout, key/IV/payload/tag tampering, wrong key, short envelope, text hashing |
| IdempotencyServiceCharacterizationTest | 1 | Eviction-driven expiry, live claims, clear/reclaim |
| MeshSimulatorCharacterizationTest | 5 | Gossip copies and TTLs, zero TTL, repeated bridge collection, outer-ID deduplication, unknown device |
| PaymentFlowCharacterizationTest | 22 | HTTP demo flow, insufficient funds, malformed inputs, rollback, database uniqueness, concurrent distinct settlements, replay and rounding limitations |

Integration tests reset accounts and ledger between cases. The HTTP-flow tests
use a separate in-memory H2 database. Concurrent tests use latches and bounded
waits and always shut down their executors. Failure-path unit tests use plain
test doubles because the bundled Mockito/Byte Buddy cannot instrument classes
on the local JDK 25; no dependencies were changed.

## Production changes

- Ingestion errors log the processing stage, ciphertext hash when available,
  bridge ID, hop count, exception type, and detail.
- Decryption and freshness rejection logs include clear reason codes and context.
- Successful processing logs the committed ledger status separately from the
  existing API response outcome. A pre-commit settlement message is now DEBUG
  and explicitly says commit is pending.
- Simulator comments describe direct service calls rather than simulated HTTP.

API status codes, response bodies and reason strings, cryptographic operations,
hash calculation, claim lifetime, settlement rules, entities, dependencies, and
configuration are unchanged. Existing README.md and mvnw.cmd edits were left alone.

## Verified behaviors requiring approval before any fix

These assertions intentionally characterize defects or limitations; passing
tests do not mean the behaviors are desirable. Update them only with approval
for the associated behavior change.

1. Insufficient funds creates a REJECTED ledger row but returns SETTLED through
   ingestion and HTTP, with no balance movement.
2. Claims remain after decryption, freshness, validation, database, and
   optimistic-lock failures. A retry is dropped even when nothing committed.
3. Padded and unpadded Base64 representations of the same encrypted bytes both
   decrypt, receive different text hashes, and can each settle. Canonicalization
   or changing the hashed representation is a core compatibility decision.
4. A 0.005 transfer is accepted. H2 rounds 4999.995 to 5000.00 and 1000.005 to
   1000.01, increasing their combined balance by 0.01. Amount scale validation
   would change accepted input behavior.
5. Re-encrypting an instruction with the same nonce produces another accepted
   payment. PIN hashes are not verified, including a null PIN hash.
6. Cache clearing followed by replay of a committed packet hits database
   uniqueness and returns INVALID/internal_error rather than DUPLICATE_DROPPED.
   Balances and the original ledger row remain intact.
7. Distinct concurrent settlements touching the same accounts can conflict.
   One commits and the other rolls back. There is no retry policy.
8. Outer-field validation annotations are not activated at bridge ingestion.
   Missing outer IDs/timestamps and negative TTLs are accepted with valid
   encrypted instructions. Errors returned by ingestion still use HTTP 200.
9. Self-transfers create a SETTLED row with no net balance change. Custom starting
   TTLs still use 5 as the hop-count baseline and can record negative hops.
10. Mesh reset clears packets and cache only; balances and ledger remain.
    Upload collection retains packets. Subsequent gossip rounds do not reduce
    the TTL of already-held copies.

Further known limitations remain: no sender signature or bridge authentication,
ephemeral RSA keys and database state, no rate limiting, exposed demo controls
and H2 console, and no distributed idempotency. React, PostgreSQL, authentication,
and Redis have not been started.

## Verification limits

The HTTP tests use MockMvc, not a real browser or external bridge. Freshness
tests exercise timestamps inside and outside the windows with timing margins;
the exact millisecond boundary is not frozen. Cache expiry is exercised without
waiting by aging a claim in the test. The tests do not establish cross-instance
or crash-recovery guarantees. Expected invalid-input tests intentionally emit
error logs and stack traces while passing.
