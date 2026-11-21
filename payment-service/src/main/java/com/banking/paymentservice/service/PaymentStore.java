package com.banking.paymentservice.service;

import com.banking.paymentservice.entity.Payment;
import com.banking.paymentservice.events.PaymentEvent;
import com.banking.paymentservice.events.PaymentEventPublisher;
import com.banking.paymentservice.repository.PaymentRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Saves one saga step. Each call is its own short transaction: no transaction is ever held open
 * while waiting for account-service (Phase 9 rule).
 *
 * <p>When the step reaches a FINAL status, the event announcing it is written to the outbox in
 * the same transaction (Phase 13). Final statuses never change, so each payment is announced once.
 */
@Component
public class PaymentStore {

    private final PaymentRepository repository;
    private final PaymentEventPublisher events;
    private final MeterRegistry meters;

    public PaymentStore(PaymentRepository repository, PaymentEventPublisher events, MeterRegistry meters) {
        this.repository = repository;
        this.events = events;
        this.meters = meters;
    }

    /**
     * @throws org.springframework.dao.DataIntegrityViolationException e.g. the Idempotency-Key is already used
     * @throws org.springframework.orm.ObjectOptimisticLockingFailureException someone else saved this payment first
     */
    @Transactional
    public Payment save(Payment payment) {
        Payment saved = repository.saveAndFlush(payment);
        if (saved.getStatus().isFinal()) {
            events.publish(PaymentEvent.of(saved));
            countFinished(saved);
        }
        return saved;
    }

    /**
     * Phase 14: banking_payments_finished_total{status, code}. Counted once per payment, like the event.
     * Both tags have a small, fixed set of values: never put IDs or amounts in tags (cardinality).
     * (Counted before the commit: a rare rollback over-counts by one. Fine for dashboards, not for accounting.)
     */
    private void countFinished(Payment payment) {
        Counter.builder("banking.payments.finished")
                .description("Payments that reached a final status")
                .tag("status", payment.getStatus().name())
                .tag("code", payment.getFailureCode() == null ? "none" : payment.getFailureCode())
                .register(meters)
                .increment();
    }
}
