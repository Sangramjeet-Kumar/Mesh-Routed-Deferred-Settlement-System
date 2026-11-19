package com.demo.upimesh.config;

import com.demo.upimesh.service.BridgeIngestionService.IngestResult;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;

/** Observes responses from both external and simulator ingestion without altering them. */
@Aspect
@Component
public class IngestionMetrics {
    private final MeterRegistry meters;
    public IngestionMetrics(MeterRegistry meters) { this.meters = meters; }

    @Around("execution(* com.demo.upimesh.service.BridgeIngestionService.ingest(..))")
    public Object observe(ProceedingJoinPoint invocation) throws Throwable {
        Timer.Sample sample = Timer.start(meters);
        String outcome = "EXCEPTION";
        try {
            Object result = invocation.proceed();
            if (result instanceof IngestResult ingest) {
                outcome = switch (ingest.outcome()) {
                    case "SETTLED", "DUPLICATE_DROPPED", "INVALID" -> ingest.outcome();
                    default -> "UNKNOWN";
                };
            }
            return result;
        } finally {
            // A bounded response label; no hashes, VPAs, bridge IDs, amounts, or error text.
            sample.stop(meters.timer("mesh.ingestion", "outcome", outcome));
        }
    }
}
