package com.demo.upimesh.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/** HTTP-only authentication and correlation; no changes to ingestion/settlement. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class HttpBoundaryFilter extends OncePerRequestFilter {
    public static final String KEY_HEADER = "X-Bridge-Api-Key";
    private static final Logger log = LoggerFactory.getLogger(HttpBoundaryFilter.class);
    private final byte[] expectedDigest;
    private final ObjectMapper json;
    private final MeterRegistry meters;

    public HttpBoundaryFilter(@Value("${upi.mesh.bridge-api-key:}") String key,
                              ObjectMapper json, MeterRegistry meters) {
        if (key.isBlank()) throw new IllegalArgumentException("Set BRIDGE_API_KEY before starting the application");
        expectedDigest = digest(key);
        this.json = json;
        this.meters = meters;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = normalizedPath(request);
        return !(path.equals("/api") || path.startsWith("/api/")
                || path.equals("/actuator") || path.startsWith("/actuator/"));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String previous = MDC.get("requestId");
        String requestId = UUID.randomUUID().toString();
        long started = System.nanoTime();
        MDC.put("requestId", requestId);
        response.setHeader("X-Request-Id", requestId);
        String path = normalizedPath(request);
        String resource = path.equals("/api/bridge") || path.startsWith("/api/bridge/") ? "bridge"
                : path.equals("/actuator/metrics") || path.startsWith("/actuator/metrics/") ? "metrics" : null;
        try {
            if (resource != null) {
                var keys = Collections.list(request.getHeaders(KEY_HEADER));
                boolean authenticated = keys.size() == 1 && !keys.get(0).isBlank()
                        && MessageDigest.isEqual(expectedDigest, digest(keys.get(0)));
                meters.counter("mesh.http.authentication", "resource", resource,
                        "result", authenticated ? "accepted" : "rejected").increment();
                if (!authenticated) {
                    response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                    response.setContentType("application/json");
                    response.setHeader("Cache-Control", "no-store");
                    json.writeValue(response.getOutputStream(), Map.of(
                            "error", "Unauthorized", "reason", "invalid_or_missing_api_key", "requestId", requestId));
                    log.warn("HTTP authentication rejected: resource={}", resource);
                    return;
                }
            }
            chain.doFilter(request, response);
        } finally {
            long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
            // Never log headers, query strings, request bodies, or raw user-provided paths.
            log.debug("HTTP request completed: method={}, status={}, durationMs={}",
                    request.getMethod(), response.getStatus(), elapsed);
            if (previous == null) MDC.remove("requestId"); else MDC.put("requestId", previous);
        }
    }

    private static String normalizedPath(HttpServletRequest request) {
        // servletPath is decoded by the container. Remove per-segment matrix parameters,
        // which MVC ignores during route matching, so they cannot bypass authentication.
        String path = request.getServletPath();
        if (path.isEmpty()) {
            path = java.net.URI.create(request.getRequestURI().substring(request.getContextPath().length())).getPath();
        }
        return path.replaceAll(";[^/]*", "");
    }

    private static byte[] digest(String value) {
        try { return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException("SHA-256 unavailable", e); }
    }
}
