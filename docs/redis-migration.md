# Redis idempotency migration

## Implementation

`IdempotencyService` is now a small contract. The runtime implementation is
`RedisIdempotencyService`, backed by Spring Data Redis/Lettuce. The old memory
implementation exists only in test sources, behind explicit test configuration.
There is no production memory fallback; an invalid store configuration fails to
provide the required bean rather than silently selecting a local cache.

Claim acquisition uses one conditional write with expiry. Keys are
`mesh:{<namespace>}:claim:<ciphertext-hash>`, storing only `claimed`. The hash
calculation is unchanged. Namespace validation excludes glob syntax/braces, and
the delimiter prevents similarly named namespaces from overlapping.

Redis enforces TTL directly. There is no Java eviction scheduler. Missing/false
claim replies are distinguished: false means duplicate, null means unconfirmed
and throws. Connection errors also propagate, stopping ingestion before decrypt
or settlement. The existing catch continues returning INVALID/internal_error;
there is no new HTTP ingestion status or automatic claim retry.

Acquired claims remain after failed processing. A timed-out command may have
executed remotely; the application does not delete its possible claim. PostgreSQL
transactions and the unique packet hash remain the financial consistency guard.
Claims and financial commits are not one cross-system transaction.

## Reset, metrics, and health

Count/reset use SCAN with the exact namespace prefix, not KEYS, FLUSHDB, FLUSHALL,
or total database size. Counting deduplicates scan repeats but is a concurrent
snapshot. Reset deletes batches of matching keys only. It can race with uploads
and affects every instance using that namespace; this remains a demo control.
SCAN-based diagnostics are O(namespace/keyspace work), not a scalable O(1) count.

The existing claim gauge reads Redis. Its value can be unavailable/NaN during an
outage; mesh-state and reset can return an error. Runtime Actuator health includes
Redis and exposes only minimal status. Other meters retain their existing labels.

## Local operation

The user's existing `upimesh-redis` container is reused without changing or
removing it. It runs Redis 8.10.3 on loopback port 6379 with AOF and a data volume.
`compose.yaml` provides an alternative pinned setup for new installations, with
no eviction, a health check, and a persistent named volume. It does not adopt
the manually created container or automatically import its data.

Environment settings are documented in `.env.example` and README. Optional Redis
settings may also be placed in ignored `.local/database.psd1` for the PowerShell
loader. Different environments must use distinct namespaces. All instances that
deduplicate the same packets must use the same database/namespace.

With AOF every-second sync, a crash may lose recent writes. Test coverage of a
graceful Redis restart is not a guarantee against disk failure, data loss, key
eviction, failover, or container-volume deletion. No Redis Cluster support or
distributed lease/retry state machine is introduced.

## Key lifecycle and deferred behavior

Application restarts retain unexpired claims, but server RSA keys still regenerate
and mesh packets remain process-local. Multi-instance ingestion requires shared
RSA keys as well as Redis; tests provision a shared key only inside the test.
Durable production key management remains separate work.

SETTLED/REJECTED disagreement, Base64 representation-sensitive hashes, missing
amount-scale validation, nonce/PIN verification gaps, and claim-on-failure rules
are unchanged. Redis does not fix those defects or guarantee eventual settlement.

## Verification approach

Default tests use a test-only memory implementation, so they need no Docker or
Redis. The old eviction characterization is retained as a historical memory test.
`-Ppostgres-it` exercises PostgreSQL independently. `-Predis-it` runs all real
integration suites, requiring Docker and dedicated PG_TEST_* settings.

Redis suites create uniquely named `upimesh-redis-it-*` containers on fixed,
available loopback ports, then remove only those containers and their anonymous
data volumes. They never stop/restart/flush the user's Redis. Read-only PING
readiness checks after restarts are test synchronization, not payment retries.

The completed verification run passed 67 Java default tests, 14 frontend tests,
and 23 integration tests (104 total). The packaged JAR was also checked against
the existing Redis container and application PostgreSQL database using a unique
smoke namespace: React/health, failed-packet claim retention, duplicate rejection
after a full JVM restart, and scoped reset. Account/ledger snapshots matched
before and after. No user Redis container restart or flush was needed.

## References

- [Redis SET command and NX/expiry options](https://redis.io/docs/latest/commands/set/)
- [Redis AOF persistence and durability tradeoffs](https://redis.io/docs/latest/operate/oss_and_stack/management/persistence/)
