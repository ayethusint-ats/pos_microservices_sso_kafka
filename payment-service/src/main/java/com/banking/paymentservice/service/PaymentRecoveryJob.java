package com.banking.paymentservice.service;

import com.banking.paymentservice.entity.Payment;
import com.banking.paymentservice.entity.PaymentStatus;
import com.banking.paymentservice.repository.PaymentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.Limit;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

/**
 * The saga's "recovery manager": finishes payments that a request left unfinished
 * (timeouts, account-service down mid-transfer, payment-service crashed between steps).
 *
 * <p>Several instances may run this at once: a payment is CLAIMED by saving it with a new
 * {@code nextRecoveryAt} under optimistic locking (@Version). The instance that loses the race
 * gets an optimistic-lock error and skips the payment. A crash after the claim just means the
 * payment comes due again later.
 */
@Component
@EnableConfigurationProperties(RecoveryProperties.class)
public class PaymentRecoveryJob {

    private static final Logger log = LoggerFactory.getLogger(PaymentRecoveryJob.class);

    private final PaymentRepository repository;
    private final PaymentStore store;
    private final TransferSaga saga;
    private final RecoveryProperties properties;

    public PaymentRecoveryJob(PaymentRepository repository, PaymentStore store, TransferSaga saga,
                              RecoveryProperties properties) {
        this.repository = repository;
        this.store = store;
        this.saga = saga;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${banking.payments.recovery.poll-interval-ms:5000}",
            initialDelayString = "${banking.payments.recovery.initial-delay-ms:10000}")
    public void run() {
        recoverDue(Instant.now());
    }

    /** @return how many payments this run worked on */
    public int recoverDue(Instant now) {
        List<Payment> due = repository.findByStatusInAndNextRecoveryAtLessThanEqualOrderByNextRecoveryAtAsc(
                PaymentStatus.IN_PROGRESS, now, Limit.of(properties.batchSize()));
        int worked = 0;
        for (Payment candidate : due) {
            Payment claimed = claim(candidate, now);
            if (claimed != null) {
                recover(claimed);
                worked++;
            }
        }
        return worked;
    }

    private Payment claim(Payment payment, Instant now) {
        payment.recoveryAttempted(now.plus(properties.backoffAfter(payment.getRecoveryAttempts() + 1)));
        try {
            return store.save(payment);
        } catch (OptimisticLockingFailureException claimedElsewhere) {
            return null;     // another instance (or the live request) got there first
        }
    }

    private void recover(Payment payment) {
        PaymentStatus before = payment.getStatus();
        try {
            payment = saga.resume(payment);
        } catch (RuntimeException ex) {
            // Unexpected (a bug, the database...): the payment comes due again after its backoff.
            log.error("Recovery of payment {} failed unexpectedly", payment.getId(), ex);
            return;
        }
        if (payment.getStatus().isFinal()) {
            log.info("Recovered payment {}: {} -> {} (attempt {})", payment.getId(), before, payment.getStatus(),
                    payment.getRecoveryAttempts());
        } else if (payment.getRecoveryAttempts() >= properties.maxAttempts()) {
            log.error("Giving up on payment {} after {} attempts in {}: needs a person", payment.getId(),
                    payment.getRecoveryAttempts(), payment.getStatus());
            payment.requireReconciliation("RECOVERY_EXHAUSTED", "Automatic recovery gave up after %d attempts in status %s (%s)"
                    .formatted(payment.getRecoveryAttempts(), payment.getStatus(), payment.getFailureReason()));
            store.save(payment);
        } else {
            log.info("Payment {} still {} after recovery attempt {}; next at {}", payment.getId(), payment.getStatus(),
                    payment.getRecoveryAttempts(), payment.getNextRecoveryAt());
        }
    }
}
