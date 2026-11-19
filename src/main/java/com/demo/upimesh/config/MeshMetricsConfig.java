package com.demo.upimesh.config;

import com.demo.upimesh.service.IdempotencyService;
import com.demo.upimesh.service.MeshSimulatorService;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class MeshMetricsConfig {
    @Bean
    MeterBinder meshStateMetrics(IdempotencyService claims, MeshSimulatorService mesh) {
        return registry -> {
            registry.gauge("mesh.idempotency.claims", claims, IdempotencyService::size);
            registry.gauge("mesh.devices", mesh, value -> value.getDevices().size());
            registry.gauge("mesh.packet.copies", mesh,
                    value -> value.getDevices().stream().mapToInt(device -> device.packetCount()).sum());
        };
    }
}
