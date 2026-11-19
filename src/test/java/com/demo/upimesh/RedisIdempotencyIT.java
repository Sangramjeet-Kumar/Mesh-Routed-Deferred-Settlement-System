package com.demo.upimesh;

import com.demo.upimesh.crypto.HybridCryptoService;
import com.demo.upimesh.model.MeshPacket;
import com.demo.upimesh.model.PaymentInstruction;
import com.demo.upimesh.model.Transaction;
import com.demo.upimesh.service.*;
import io.lettuce.core.ClientOptions;
import io.lettuce.core.SocketOptions;
import org.junit.jupiter.api.*;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RedisIdempotencyIT {
    private RedisTestServer server;
    private Client first;
    private Client second;
    private final String namespace = "test:" + UUID.randomUUID().toString().replace("-", "");
    private RedisIdempotencyService claims;

    @BeforeAll void initialize() throws Exception {
        server = RedisTestServer.start();
        first = new Client(server.port()); second = new Client(server.port());
        claims = store(first, 86400, namespace);
    }
    @BeforeEach void clear() { claims.clear(); }
    @AfterAll void cleanup() throws Exception {
        if (first != null) first.close();
        if (second != null) second.close();
        if (server != null) server.close();
    }

    @Test void independentClientsRaceAndOnlyOneClaimWins() throws Exception {
        var other = store(second, 86400, namespace);
        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        try {
            var results = new ArrayList<Future<Boolean>>();
            for (int i = 0; i < 32; i++) {
                var client = i % 2 == 0 ? claims : other;
                results.add(pool.submit(() -> { start.await(); return client.claim("same"); }));
            }
            start.countDown(); int winners = 0;
            for (var result : results) if (result.get(10, TimeUnit.SECONDS)) winners++;
            assertEquals(1, winners);
            assertEquals(1, claims.size());
            Long ttl = first.template.getExpire(key("same"));
            assertNotNull(ttl); assertTrue(ttl > 0 && ttl <= 86400);
        } finally { pool.shutdownNow(); assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS)); }
    }

    @Test void expiryIsAtomicAndNeedsNoEvictionScheduler() throws Exception {
        var expiring = store(first, 1, namespace);
        assertTrue(expiring.claim("expires"));
        assertFalse(expiring.claim("expires"));
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (Boolean.TRUE.equals(first.template.hasKey(key("expires"))) && System.nanoTime() < deadline) Thread.sleep(50);
        assertFalse(Boolean.TRUE.equals(first.template.hasKey(key("expires"))));
        assertTrue(expiring.claim("expires"));
    }

    @Test void resetAndCountAreScopedEvenForSimilarlyNamedNamespaces() {
        var sibling = store(second, 86400, namespace + ":child");
        sibling.claim("other");
        first.template.opsForValue().set("learning:packet", "unrelated");
        for (int i = 0; i < 450; i++) claims.claim("packet-" + i);
        assertEquals(450, claims.size());
        claims.clear();
        assertEquals(0, claims.size());
        assertFalse(sibling.claim("other"));
        assertEquals("unrelated", first.template.opsForValue().get("learning:packet"));
    }

    @Test void redisRestartAndClientRestartPreserveUnexpiredClaim() throws Exception {
        assertTrue(claims.claim("durable"));
        server.restart();
        first.awaitReady(); second.awaitReady();
        try (Client reconnected = new Client(server.port())) {
            assertFalse(store(reconnected, 86400, namespace).claim("durable"));
            assertEquals("claimed", reconnected.template.opsForValue().get(key("durable")));
        }
    }

    @Test void failedProcessingRetainsClaimAndOutageNeverActsAsDuplicateOrFallback() throws Exception {
        var bridge = new BridgeIngestionService();
        var settlement = new SettlementService() {
            @Override public Transaction settle(PaymentInstruction instruction, String hash, String node, int hops) {
                throw new AssertionError("Settlement must not run after a claim/decrypt failure");
            }
        };
        ReflectionTestUtils.setField(bridge, "crypto", new HybridCryptoService());
        ReflectionTestUtils.setField(bridge, "idempotency", claims);
        ReflectionTestUtils.setField(bridge, "settlement", settlement);
        MeshPacket packet = new MeshPacket(); packet.setCiphertext("!");
        server.stop();
        try {
            assertThrows(RuntimeException.class, () -> claims.claim("outage"));
            var result = bridge.ingest(packet, "bridge", 0);
            assertEquals("INVALID", result.outcome());
            assertTrue(result.reason().startsWith("internal_error: "));
        } finally { server.resume(); first.awaitReady(); second.awaitReady(); }
        assertEquals("decryption_failed", bridge.ingest(packet, "bridge", 0).reason());
        assertEquals("DUPLICATE_DROPPED", bridge.ingest(packet, "bridge", 0).outcome());
        // A timed-out write can be ambiguous; do not assume its key is absent.
        assertTrue(claims.claim("recovered-new-packet"));
    }

    @Test void invalidTtlAndWildcardNamespaceAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> store(first, 0, namespace));
        assertThrows(IllegalArgumentException.class, () -> store(first, 1, "unsafe*"));
    }

    private String key(String hash) { return "mesh:{" + namespace + "}:claim:" + hash; }
    private RedisIdempotencyService store(Client client, long ttl, String name) {
        return new RedisIdempotencyService(client.template, ttl, name);
    }

    private static class Client implements AutoCloseable {
        final LettuceConnectionFactory factory;
        final StringRedisTemplate template;
        Client(int port) {
            var options = ClientOptions.builder().socketOptions(SocketOptions.builder().connectTimeout(Duration.ofMillis(500)).build()).build();
            var config = LettuceClientConfiguration.builder().commandTimeout(Duration.ofMillis(500))
                    .shutdownTimeout(Duration.ZERO).clientOptions(options).build();
            factory = new LettuceConnectionFactory(new RedisStandaloneConfiguration("127.0.0.1", port), config);
            factory.afterPropertiesSet(); factory.start();
            template = new StringRedisTemplate(factory);
        }
        @Override public void close() { factory.destroy(); }
        void awaitReady() throws Exception {
            long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
            RuntimeException last = null;
            do {
                try (var connection = factory.getConnection()) {
                    if ("PONG".equals(connection.ping())) return;
                } catch (RuntimeException e) { last = e; }
                Thread.sleep(100);
            } while (System.nanoTime() < deadline);
            throw new IllegalStateException("Client did not reconnect to test Redis", last);
        }
    }
}
