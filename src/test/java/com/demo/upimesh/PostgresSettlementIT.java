package com.demo.upimesh;

import com.demo.upimesh.crypto.ServerKeyHolder;
import com.demo.upimesh.model.*;
import com.demo.upimesh.service.*;
import org.junit.jupiter.api.*;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

/** Opt-in real-database suite. Each run owns and removes only its random schema. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PostgresSettlementIT {
    protected final String schema = "mesh_it_" + UUID.randomUUID().toString().replace("-", "");
    private String url;
    private String username;
    private String password;
    private boolean schemaCreated;
    protected ConfigurableApplicationContext context;
    private AccountRepository accounts;
    private TransactionRepository transactions;
    private DemoService demo;
    private BridgeIngestionService bridge;
    private IdempotencyService claims;

    @BeforeAll
    void initialize() throws Exception {
        url = required("PG_TEST_URL");
        username = required("PG_TEST_USERNAME");
        password = required("PG_TEST_PASSWORD");
        prepareStore();
        String databasePath = java.net.URI.create(url.substring("jdbc:".length())).getPath();
        assertTrue(databasePath.endsWith("_test"), "Integration database name must end in _test");
        assertNotEquals(System.getenv("DB_URL"), url, "Never use the application database for integration tests");
        try (Connection connection = connection(); var statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA " + schema);
            schemaCreated = true;
        }
        start();
    }

    protected boolean useRedis() { return false; }
    protected void prepareStore() throws Exception { }
    protected void closeStore() throws Exception { }
    protected int redisPort() { return 6379; }

    private void start() {
        // CLI properties override the isolated H2 defaults in src/test/resources.
        var builder = new SpringApplicationBuilder(UpiMeshApplication.class).web(WebApplicationType.NONE);
        if (!useRedis()) builder.sources(MemoryIdempotencyConfig.class);
        context = builder.run(
                "--upi.mesh.idempotency-store=" + (useRedis() ? "redis" : "test"),
                "--upi.mesh.redis.namespace=" + schema,
                "--spring.data.redis.host=127.0.0.1",
                "--spring.data.redis.port=" + redisPort(),
                "--spring.data.redis.connect-timeout=500ms", "--spring.data.redis.timeout=500ms",
                "--spring.datasource.url=" + url,
                "--spring.datasource.username=" + username,
                "--spring.datasource.password=" + password,
                "--spring.datasource.driver-class-name=org.postgresql.Driver",
                "--spring.datasource.hikari.schema=" + schema,
                "--spring.jpa.properties.hibernate.default_schema=" + schema,
                "--spring.jpa.hibernate.ddl-auto=validate",
                "--spring.flyway.enabled=true",
                "--spring.flyway.locations=classpath:db/migration/postgresql",
                "--spring.flyway.schemas=" + schema,
                "--spring.flyway.default-schema=" + schema,
                "--spring.flyway.clean-disabled=true",
                "--debug=false", "--logging.level.root=WARN", "--spring.main.banner-mode=off");
        accounts = context.getBean(AccountRepository.class);
        transactions = context.getBean(TransactionRepository.class);
        demo = context.getBean(DemoService.class);
        bridge = context.getBean(BridgeIngestionService.class);
        claims = context.getBean(IdempotencyService.class);
    }

    @BeforeEach
    void reset() {
        transactions.deleteAll();
        accounts.deleteAll();
        demo.seedAccounts();
        claims.clear();
    }

    @AfterAll
    void cleanup() throws Exception {
        try {
            if (context != null) {
                try { if (useRedis()) claims.clear(); } finally { context.close(); }
            }
        } finally {
            try { closeStore(); } finally {
                if (schemaCreated && schema.matches("mesh_it_[0-9a-f]{32}")) {
                    try (Connection connection = connection(); var statement = connection.createStatement()) {
                        statement.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
                    }
                }
            }
        }
    }

    @Test
    void migrationRunsOnceAndSchemaMatchesUnchangedEntities() throws Exception {
        assertEquals(4, accounts.count());
        try (Connection connection = connection(); var statement = connection.createStatement();
             var rows = statement.executeQuery("SELECT count(*) FROM " + schema + ".flyway_schema_history WHERE success")) {
            assertTrue(rows.next());
            assertEquals(1, rows.getInt(1));
        }
    }

    @Test
    void threeConcurrentUploadsSettleExactlyOnce() throws Exception {
        MeshPacket packet = packet("100.00");
        CountDownLatch ready = new CountDownLatch(3);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(3);
        try {
            List<Future<String>> results = new ArrayList<>();
            for (int i = 0; i < 3; i++) {
                String node = "bridge-" + i;
                results.add(pool.submit(() -> {
                    ready.countDown(); await(release);
                    return bridge.ingest(packet, node, 1).outcome();
                }));
            }
            await(ready); release.countDown();
            List<String> outcomes = new ArrayList<>();
            for (Future<String> result : results) outcomes.add(result.get(15, TimeUnit.SECONDS));
            assertEquals(1, outcomes.stream().filter("SETTLED"::equals).count());
            assertEquals(2, outcomes.stream().filter("DUPLICATE_DROPPED"::equals).count());
        } finally { stop(pool); }
        balances("4900.00", "1100.00");
        assertEquals(1, transactions.count());
    }

    @Test
    void uniqueHashRollsBackReplayAfterClaimClearing() throws Exception {
        MeshPacket packet = packet("10.00");
        assertEquals("SETTLED", bridge.ingest(packet, "first", 1).outcome());
        claims.clear();
        var replay = bridge.ingest(packet, "second", 2);
        assertEquals("INVALID", replay.outcome());
        assertTrue(replay.reason().startsWith("internal_error: "));
        balances("4990.00", "1010.00");
        assertEquals(1, transactions.count());
        assertEquals("DUPLICATE_DROPPED", bridge.ingest(packet, "third", 3).outcome());
    }

    @Test
    void failedLedgerInsertRollsBackAndRetainsClaim() throws Exception {
        MeshPacket packet = packet("10.00");
        assertEquals("INVALID", bridge.ingest(packet, null, 0).outcome());
        balances("5000.00", "1000.00");
        assertEquals(0, transactions.count());
        assertEquals("DUPLICATE_DROPPED", bridge.ingest(packet, "retry", 0).outcome());
    }

    @Test
    void insufficientFundsKeepsLegacySettledResponseAndRejectedLedger() throws Exception {
        assertEquals("SETTLED", bridge.ingest(packet("6000.00"), "bridge", 0).outcome());
        assertEquals(Transaction.Status.REJECTED, transactions.findAll().get(0).getStatus());
        balances("5000.00", "1000.00");
    }

    @Test
    void numericScaleRetainsExistingIndependentRoundingBehavior() throws Exception {
        assertEquals("SETTLED", bridge.ingest(packet("0.005"), "bridge", 0).outcome());
        balances("5000.00", "1000.01");
        assertEquals(new BigDecimal("0.01"), transactions.findAll().get(0).getAmount());
    }

    @Test
    void distinctConcurrentPaymentsConflictWithoutPartialCommit() throws Exception {
        CountDownLatch loaded = new CountDownLatch(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<Boolean>> results = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                String hash = Integer.toString(i).repeat(64);
                results.add(pool.submit(() -> {
                    try {
                        new TransactionTemplate(context.getBean(PlatformTransactionManager.class)).executeWithoutResult(status -> {
                            accounts.findById("alice@demo").orElseThrow();
                            accounts.findById("bob@demo").orElseThrow();
                            loaded.countDown(); await(loaded);
                            context.getBean(SettlementService.class).settle(new PaymentInstruction(
                                    "alice@demo", "bob@demo", BigDecimal.TEN, "unused", hash,
                                    Instant.now().toEpochMilli()), hash, "bridge", 1);
                        });
                        return true;
                    } catch (OptimisticLockingFailureException expected) { return false; }
                }));
            }
            int committed = 0;
            for (Future<Boolean> result : results) if (result.get(15, TimeUnit.SECONDS)) committed++;
            assertEquals(1, committed);
        } finally { stop(pool); }
        balances("4990.00", "1010.00");
        assertEquals(1, transactions.count());
    }

    @Test
    void restartPreservesMoneyAndLedgerAndRegeneratesKeys() throws Exception {
        MeshPacket undelivered = packet("5.00");
        var settled = bridge.ingest(packet("10.00"), "bridge", 1);
        String oldKey = context.getBean(ServerKeyHolder.class).getPublicKeyBase64();
        context.close(); start();
        balances("4990.00", "1010.00");
        assertTrue(transactions.existsById(settled.transactionId()));
        assertEquals(useRedis() ? 1 : 0, claims.size());
        assertNotEquals(oldKey, context.getBean(ServerKeyHolder.class).getPublicKeyBase64());
        assertEquals("decryption_failed", bridge.ingest(undelivered, "bridge", 0).reason());
        migrationRunsOnceAndSchemaMatchesUnchangedEntities();
    }

    private Connection connection() throws Exception { return DriverManager.getConnection(url, username, password); }
    private MeshPacket packet(String amount) throws Exception {
        return demo.createPacket("alice@demo", "bob@demo", new BigDecimal(amount), "1234", 5);
    }
    private void balances(String alice, String bob) {
        assertEquals(new BigDecimal(alice), accounts.findById("alice@demo").orElseThrow().getBalance());
        assertEquals(new BigDecimal(bob), accounts.findById("bob@demo").orElseThrow().getBalance());
    }
    private static String required(String key) {
        String value = System.getenv(key);
        if (value == null || value.isBlank()) throw new IllegalStateException("Set " + key + " before running -Ppostgres-it");
        return value;
    }
    private static void await(CountDownLatch latch) {
        try { if (!latch.await(10, TimeUnit.SECONDS)) throw new AssertionError("Concurrent workers did not synchronize"); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
    }
    private static void stop(ExecutorService pool) throws InterruptedException {
        pool.shutdownNow(); assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));
    }
}
