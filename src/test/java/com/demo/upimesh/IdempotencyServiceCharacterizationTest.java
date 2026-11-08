package com.demo.upimesh;

import com.demo.upimesh.service.IdempotencyService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class IdempotencyServiceCharacterizationTest {
    @Test
    @SuppressWarnings("unchecked")
    void claimsExpireOnlyWhenEvictionRunsAndLiveClaimsRemain() {
        IdempotencyService service = new IdempotencyService();
        ReflectionTestUtils.setField(service, "ttlSeconds", 60L);
        assertTrue(service.claim("expired"));
        assertTrue(service.claim("live"));
        Map<String, Instant> entries = (Map<String, Instant>) ReflectionTestUtils.getField(service, "seen");
        entries.put("expired", Instant.now().minusSeconds(120));

        assertFalse(service.claim("expired"), "Claim does not check age itself");
        service.evictExpired();
        assertEquals(1, service.size());
        assertFalse(service.claim("live"));
        assertTrue(service.claim("expired"));
        service.clear();
        assertEquals(0, service.size());
        assertTrue(service.claim("live"));
    }
}
