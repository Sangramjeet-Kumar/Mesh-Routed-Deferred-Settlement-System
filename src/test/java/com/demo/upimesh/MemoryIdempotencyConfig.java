package com.demo.upimesh;

import com.demo.upimesh.service.IdempotencyService;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/** Test only: never packaged as a runtime fallback. */
@TestConfiguration(proxyBeanMethods = false)
public class MemoryIdempotencyConfig {
    @Bean
    IdempotencyService testClaims() { return new InMemoryIdempotencyService(); }
}
