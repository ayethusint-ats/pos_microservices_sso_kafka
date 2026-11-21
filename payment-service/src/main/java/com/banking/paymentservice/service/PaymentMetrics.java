package com.banking.paymentservice.service;

import com.banking.paymentservice.entity.PaymentStatus;
import com.banking.paymentservice.repository.PaymentRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.util.EnumSet;

/**
 * Phase 14: banking_payments_open{status}: how many payments are unfinished, or waiting for a person.
 * Read from the database at each scrape (a COUNT on an indexed column), so the value is right
 * across all instances and restarts, unlike an in-memory counter.
 *
 * <p>What to watch: PENDING/DEBITED/COMPENSATING should go back to ~0 within minutes (the recovery
 * job); NEEDS_RECONCILIATION growing means people have work.
 */
@Component
public class PaymentMetrics {

    public PaymentMetrics(PaymentRepository repository, MeterRegistry registry) {
        EnumSet<PaymentStatus> watched = EnumSet.copyOf(PaymentStatus.IN_PROGRESS);
        watched.add(PaymentStatus.NEEDS_RECONCILIATION);
        for (PaymentStatus status : watched) {
            Gauge.builder("banking.payments.open", repository, r -> r.countByStatus(status))
                    .description("Payments currently in this unfinished status")
                    .tag("status", status.name())
                    .register(registry);
        }
    }
}
