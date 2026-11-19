package com.demo.upimesh;

import com.demo.upimesh.crypto.ServerKeyHolder;
import com.demo.upimesh.model.AccountRepository;
import com.demo.upimesh.model.TransactionRepository;
import com.demo.upimesh.service.BridgeIngestionService;
import com.demo.upimesh.service.DemoService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.test.util.ReflectionTestUtils;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

/** Runs the PostgreSQL settlement contract with real shared Redis claims. */
class RedisPostgresIT extends PostgresSettlementIT {
    private RedisTestServer redis;
    @Override protected boolean useRedis() { return true; }
    @Override protected void prepareStore() throws Exception { redis = RedisTestServer.start(); }
    @Override protected int redisPort() { return redis.port(); }
    @Override protected void closeStore() throws Exception { if (redis != null) redis.close(); }

    @Test
    void twoApplicationContextsSharingKeysAndRedisSettleOneDelivery() throws Exception {
        var arguments = new ArrayList<String>();
        for (String property : List.of("spring.datasource.url", "spring.datasource.username", "spring.datasource.password",
                "spring.datasource.driver-class-name", "spring.datasource.hikari.schema", "spring.jpa.properties.hibernate.default_schema",
                "spring.jpa.hibernate.ddl-auto", "spring.flyway.enabled", "spring.flyway.locations", "spring.flyway.schemas",
                "spring.flyway.default-schema", "spring.flyway.clean-disabled", "upi.mesh.idempotency-store",
                "upi.mesh.redis.namespace", "spring.data.redis.host", "spring.data.redis.port")) {
            arguments.add("--" + property + "=" + context.getEnvironment().getProperty(property));
        }
        arguments.addAll(List.of("--debug=false", "--logging.level.root=WARN", "--spring.main.banner-mode=off"));
        try (var second = new SpringApplicationBuilder(UpiMeshApplication.class).web(WebApplicationType.NONE)
                .run(arguments.toArray(String[]::new))) {
            // Test-only shared key provisioning: production key lifecycle remains unchanged.
            Object keys = ReflectionTestUtils.getField(context.getBean(ServerKeyHolder.class), "keyPair");
            ReflectionTestUtils.setField(second.getBean(ServerKeyHolder.class), "keyPair", keys);
            var packet = context.getBean(DemoService.class).createPacket("alice@demo", "bob@demo", new BigDecimal("100.00"), "1234", 5);
            var pool = Executors.newFixedThreadPool(2);
            var release = new CountDownLatch(1);
            try {
                Future<String> first = pool.submit(() -> { release.await(); return context.getBean(BridgeIngestionService.class).ingest(packet, "first-instance", 1).outcome(); });
                Future<String> other = pool.submit(() -> { release.await(); return second.getBean(BridgeIngestionService.class).ingest(packet, "second-instance", 1).outcome(); });
                release.countDown();
                var outcomes = List.of(first.get(10, TimeUnit.SECONDS), other.get(10, TimeUnit.SECONDS));
                assertEquals(1, outcomes.stream().filter("SETTLED"::equals).count());
                assertEquals(1, outcomes.stream().filter("DUPLICATE_DROPPED"::equals).count());
            } finally { pool.shutdownNow(); assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS)); }
            assertEquals(1, context.getBean(TransactionRepository.class).count());
            assertEquals(new BigDecimal("4900.00"), second.getBean(AccountRepository.class).findById("alice@demo").orElseThrow().getBalance());
            assertEquals(new BigDecimal("1100.00"), second.getBean(AccountRepository.class).findById("bob@demo").orElseThrow().getBalance());
        }
    }
}
