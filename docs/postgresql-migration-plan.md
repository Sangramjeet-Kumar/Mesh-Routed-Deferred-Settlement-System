# PostgreSQL migration — implementation and verification

The user approved this phase and it is implemented. PostgreSQL is the runtime
database; H2 is test-only. Payment services, cryptography, JPA entities, API
controllers, and the React frontend remain unchanged.

## Verified result

- Spring Boot 3.3.5 and Java 17 target remain unchanged. Flyway 11.14.1 and the
  PostgreSQL-specific Flyway module were added; the Boot-managed JDBC driver is
  42.7.4. Migration and schema validation succeeded on the local PostgreSQL 18
  server with these versions.
- `V1__create_accounts_and_transactions.sql` creates the existing entity mappings,
  including NUMERIC(19,2), identity IDs, timestamp precision, optimistic versions,
  required fields, ledger statuses, and unique packet hash. No new amount rules,
  foreign keys, or balance constraints were introduced.
- Runtime uses environment-based credentials, Flyway migration, Hibernate
  `validate`, and disabled Flyway clean. H2 and its console are absent at runtime.
- Dedicated `upimesh` and `upimesh_test` databases and non-admin roles were
  provisioned. Generated credentials are in ignored `.local/database.psd1`.
  `scripts/load-database-env.ps1` loads them without printing them. Administrator
  credentials were used interactively for provisioning and were not stored.
- `verify -Ppostgres-it` passes all 68 cases: 46 existing Java cases, 14 frontend
  cases, and 8 new real-PostgreSQL integration cases. Default `verify` keeps its
  isolated H2 tests and does not require PostgreSQL access.
- Integration tests use and remove their own random schema in a database whose
  name ends in `_test`, and reject the application's database URL.
- Restart coverage confirms persistent balances/ledger and unchanged seeding,
  while documenting regenerated RSA keys and an empty in-memory cache.
- The executable JAR was started with PostgreSQL and served the React root page,
  four seeded accounts, and five mesh devices through HTTP successfully.

## Original approved scope

## Scope

1. Add PostgreSQL JDBC support and environment-based connection settings. Make
   PostgreSQL the normal application database; retain H2 for isolated existing
   characterization tests rather than requiring a developer database for them.
2. Add versioned Flyway schema migrations and use Hibernate `validate` against
   PostgreSQL. Preserve the existing account and transaction mappings, numeric
   precision/scale, identity IDs, status representation, optimistic versions,
   and unique packet hash. Do not use `create-drop` against persistent data.
3. Keep existing demo account seeding behavior: seed only when the account table
   is empty. Use a dedicated project database and isolated integration-test
   databases. Do not alter unrelated databases or PostgreSQL server settings.
4. Verify JDBC/Flyway compatibility with the installed PostgreSQL 18 service
   before selecting versions. Avoid a broad Spring Boot/framework upgrade.
5. Add real PostgreSQL integration coverage for schema initialization/validation,
   same-packet concurrent uploads, database uniqueness, atomic rollback, distinct
   payment conflicts, numeric persistence, and retention across application
   restarts. Keep the existing H2 and frontend suites.
6. Update README setup/run/test commands, architecture persistence notes, API
   limitations, and project structure together with the implemented changes.

## Boundaries and risks

- Payment instructions, hybrid encryption, Base64-text hashing, claim retention,
  API outcomes, accepted amounts, and frontend behavior remain unchanged.
- PostgreSQL numeric rounding, timestamp precision, constraint exceptions, and
  transaction conflicts must be characterized on PostgreSQL itself. Do not copy
  H2-specific assertions blindly or normalize existing internal-error messages.
- Persistent balances/ledger are an intentional lifecycle change. Existing H2
  data is ephemeral; this phase does not assume an existing dataset to import.
- The database can survive restart while RSA keys, mesh packets, and cache claims
  still do not. Previously encrypted packets can become undecryptable after key
  regeneration. Durable key management is a separate decision, not a hidden part
  of the database migration.
- Database uniqueness remains a durable backstop; Redis and distributed claim
  state stay deferred. The known outcome and retry inconsistencies remain.
- Credentials belong in local environment settings, never committed source or
  README examples containing real passwords. Real integration tests must target
  a disposable/dedicated database, never an unrelated existing schema.

## Remaining boundaries

Authentication/observability and Redis require a later approved phase. Known
payment behavior fixes remain deferred. No application balances or ledger rows
were created by the integration suite; its changes were confined to the test
database. Runtime smoke checks only read application state after initial seeding.
