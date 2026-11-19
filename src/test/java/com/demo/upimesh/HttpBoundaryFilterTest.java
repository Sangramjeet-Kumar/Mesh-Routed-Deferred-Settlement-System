package com.demo.upimesh;

import com.demo.upimesh.config.HttpBoundaryFilter;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class HttpBoundaryFilterTest {
    private static final String KEY = "test-only-secret-that-is-not-a-deployment-key";

    @ParameterizedTest
    @ValueSource(strings = {"/api/bridge/ingest", "/api/bridge/ingest/", "/api/bridge/ingest;x=y",
            "/api/bridge;x=y/ingest", "/api/bridge/other", "/actuator/metrics", "/actuator/metrics/jvm.memory.used"})
    void protectedPathsRejectBeforeReadingBody(String path) throws Exception {
        var registry = new SimpleMeterRegistry();
        try {
            var request = new MockHttpServletRequest("POST", path);
            request.setServletPath(path);
            request.setContent("body-must-not-be-read".getBytes(StandardCharsets.UTF_8));
            var response = new MockHttpServletResponse();
            var called = new AtomicBoolean();
            new HttpBoundaryFilter(KEY, new ObjectMapper(), registry).doFilter(request, response,
                    (req, res) -> called.set(true));
            assertEquals(401, response.getStatus());
            assertFalse(called.get());
            assertFalse(response.getContentAsString().contains(KEY));
            assertEquals("body-must-not-be-read", new String(request.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
            assertEquals("no-store", response.getHeader("Cache-Control"));
            assertNull(MDC.get("requestId"));
        } finally { registry.close(); }
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/accounts", "/api/demo/send", "/api/mesh/flush", "/actuator/health", "/", "/assets/main.js"})
    void demoReadsAndSimulatorRemainAccessible(String path) throws Exception {
        var registry = new SimpleMeterRegistry();
        try {
            var request = new MockHttpServletRequest("GET", path);
            request.setServletPath(path);
            var called = new AtomicBoolean();
            new HttpBoundaryFilter(KEY, new ObjectMapper(), registry).doFilter(request, new MockHttpServletResponse(),
                    (req, res) -> called.set(true));
            assertTrue(called.get());
        } finally { registry.close(); }
    }

    @Test
    void validKeyPreservesBodyAndRestoresOuterCorrelationContext() throws Exception {
        var registry = new SimpleMeterRegistry();
        try {
            var request = new MockHttpServletRequest("POST", "/app/api/bridge/ingest");
            request.setContextPath("/app"); // Empty servlet path also exercises the fallback.
            request.addHeader(HttpBoundaryFilter.KEY_HEADER, KEY);
            request.setContent("encrypted-body".getBytes(StandardCharsets.UTF_8));
            var response = new MockHttpServletResponse();
            MDC.put("requestId", "outer");
            try {
                new HttpBoundaryFilter(KEY, new ObjectMapper(), registry).doFilter(request, response, (req, res) -> {
                    assertNotEquals("outer", MDC.get("requestId"));
                    assertEquals("encrypted-body", new String(req.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
                });
                assertEquals("outer", MDC.get("requestId"));
                assertEquals(1, registry.get("mesh.http.authentication").tag("result", "accepted").counter().count());
            } finally { MDC.remove("requestId"); }
        } finally { registry.close(); }
    }

    @Test
    void blankConfigurationFailsClosedAtStartup() {
        var registry = new SimpleMeterRegistry();
        try {
            assertThrows(IllegalArgumentException.class, () -> new HttpBoundaryFilter("  ", new ObjectMapper(), registry));
        } finally { registry.close(); }
    }
}
