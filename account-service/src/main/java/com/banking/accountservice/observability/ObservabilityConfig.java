package com.banking.accountservice.observability;

import io.micrometer.observation.ObservationPredicate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.server.observation.ServerRequestObservationContext;

/** Phase 14: what NOT to observe. */
@Configuration(proxyBeanMethods = false)
public class ObservabilityConfig {

    /**
     * Found live: every Prometheus scrape (each 15 s) and every health probe became a trace, burying
     * the real ones. /actuator requests get neither a trace nor an http.server.requests metric
     * (scrapes would also distort the latency and request-rate panels).
     */
    @Bean
    ObservationPredicate noActuatorObservations() {
        return (name, context) -> !(context instanceof ServerRequestObservationContext request
                && request.getCarrier().getRequestURI().startsWith("/actuator"));
    }
}
