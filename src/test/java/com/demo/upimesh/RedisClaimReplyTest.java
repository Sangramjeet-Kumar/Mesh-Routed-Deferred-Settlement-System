package com.demo.upimesh;

import com.demo.upimesh.service.RedisIdempotencyService;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import java.lang.reflect.Proxy;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RedisClaimReplyTest {
    @Test
    @SuppressWarnings("unchecked")
    void unconfirmedReplyIsAnErrorNotADuplicate() {
        ValueOperations<String, String> operations = (ValueOperations<String, String>) Proxy.newProxyInstance(
                ValueOperations.class.getClassLoader(), new Class<?>[]{ValueOperations.class},
                (proxy, method, arguments) -> null);
        var template = new StringRedisTemplate() {
            @Override public ValueOperations<String, String> opsForValue() { return operations; }
        };
        var claims = new RedisIdempotencyService(template, 86400, "test");
        assertThrows(IllegalStateException.class, () -> claims.claim("hash"));
    }
}
