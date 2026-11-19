package com.demo.upimesh.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Service
@ConditionalOnProperty(name = "upi.mesh.idempotency-store", havingValue = "redis", matchIfMissing = true)
public class RedisIdempotencyService implements IdempotencyService {
    private final StringRedisTemplate redis;
    private final Duration ttl;
    private final String prefix;

    public RedisIdempotencyService(StringRedisTemplate redis,
            @Value("${upi.mesh.idempotency-ttl-seconds:86400}") long ttlSeconds,
            @Value("${upi.mesh.redis.namespace:upimesh:demo}") String namespace) {
        if (ttlSeconds <= 0) throw new IllegalArgumentException("Idempotency TTL must be positive");
        if (!namespace.matches("[A-Za-z0-9:_-]{1,100}"))
            throw new IllegalArgumentException("Invalid Redis namespace: use letters, digits, colon, underscore or hyphen");
        this.redis = redis;
        this.ttl = Duration.ofSeconds(ttlSeconds);
        this.prefix = "mesh:{" + namespace + "}:claim:";
    }

    @Override
    public boolean claim(String packetHash) {
        Boolean claimed = redis.opsForValue().setIfAbsent(prefix + packetHash, "claimed", ttl);
        if (claimed == null) throw new IllegalStateException("Redis did not confirm the idempotency claim");
        return claimed;
    }

    @Override
    public int size() {
        // Diagnostic snapshot: SCAN can repeat keys or race with expiry/uploads.
        Set<String> keys = new HashSet<>();
        try (var cursor = redis.scan(options())) { cursor.forEachRemaining(keys::add); }
        return keys.size();
    }

    @Override
    public void clear() {
        // Never FLUSHDB, FLUSHALL or blocking KEYS. Other namespaces are untouched.
        try (var cursor = redis.scan(options())) {
            List<String> batch = new ArrayList<>(200);
            while (cursor.hasNext()) {
                batch.add(cursor.next());
                if (batch.size() == 200) { redis.delete(batch); batch.clear(); }
            }
            if (!batch.isEmpty()) redis.delete(batch);
        }
    }

    private ScanOptions options() { return ScanOptions.scanOptions().match(prefix + "*").count(200).build(); }
}
