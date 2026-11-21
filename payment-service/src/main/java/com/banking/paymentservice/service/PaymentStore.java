package com.banking.paymentservice.service;

import com.banking.paymentservice.entity.Payment;
import com.banking.paymentservice.events.PaymentEvent;
import com.banking.paymentservice.events.PaymentEventPublisher;
import com.banking.paymentservice.repository.PaymentRepository;
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

    public PaymentStore(PaymentRepository repository, PaymentEventPublisher events) {
        this.repository = repository;
        this.events = events;
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
        }
        return saved;
    }
}
