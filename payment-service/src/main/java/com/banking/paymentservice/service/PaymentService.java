package com.banking.paymentservice.service;

import com.banking.paymentservice.dto.PaymentResponse;
import com.banking.paymentservice.dto.TransferRequest;
import com.banking.paymentservice.entity.Payment;
import com.banking.paymentservice.entity.PaymentStatus;
import com.banking.paymentservice.exception.IdempotencyKeyReusedException;
import com.banking.paymentservice.exception.PaymentNotCompletedException;
import com.banking.paymentservice.exception.ResourceNotFoundException;
import com.banking.paymentservice.mapper.PaymentMapper;
import com.banking.paymentservice.repository.PaymentRepository;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;

/**
 * Entry point for transfers: Idempotency-Key handling, then the saga ({@link TransferSaga}).
 *
 * <p>Why this class is NOT {@code @Transactional}: the money moves in ANOTHER service's
 * database. Each saga step is saved in its own short transaction ({@link PaymentStore}), and the
 * payment record tells exactly how far the transfer got.
 *
 * <p>Two layers of idempotency (Phase 13):
 * <pre>
 *   client  ──Idempotency-Key──►  payment-service   same key → same payment, executed once
 *   payment-service ──payment ID──► account-service  same payment → each money movement once
 * </pre>
 */
@Service
public class PaymentService {

    private static final Logger audit = LoggerFactory.getLogger("AUDIT");

    private final PaymentRepository repository;
    private final PaymentStore store;
    private final TransferSaga saga;
    private final RecoveryProperties recovery;
    private final ObservationRegistry observations;

    public PaymentService(PaymentRepository repository, PaymentStore store, TransferSaga saga, RecoveryProperties recovery,
                          ObservationRegistry observations) {
        this.repository = repository;
        this.store = store;
        this.saga = saga;
        this.recovery = recovery;
        this.observations = observations;
    }

    /**
     * @param idempotencyKey optional; when the same customer sends the same key again, the payment
     *                       created by the first request is returned and nothing is executed again
     * @return the payment when it COMPLETED, or is still in progress (PENDING / DEBITED /
     *         COMPENSATING: the recovery job finishes it) or NEEDS_RECONCILIATION
     * @throws PaymentNotCompletedException when the transfer did not happen (REJECTED, FAILED,
     *         REVERSED). The payment is still recorded, and the exception carries it.
     * @throws IdempotencyKeyReusedException the key was used before for a different request
     */
    public TransferResult transfer(String payerId, String idempotencyKey, TransferRequest request) {
        // Phase 14: ONE Observation gives both a timer (banking_transfer_seconds{status, replayed}) and a
        // span "transfer" in the trace, parent of the calls to account-service.
        Observation observation = Observation.createNotStarted("banking.transfer", observations)
                .contextualName("transfer")
                .lowCardinalityKeyValue("idempotency_key", idempotencyKey == null ? "absent" : "present")
                .start();
        try (Observation.Scope ignored = observation.openScope()) {
            TransferResult result = execute(payerId, idempotencyKey, request);
            tag(observation, result.payment(), result.replayed());
            return result;
        } catch (PaymentNotCompletedException ex) {
            tag(observation, ex.getPayment(), ex.isReplayed());
            throw ex;
        } catch (RuntimeException ex) {
            observation.lowCardinalityKeyValue("status", "ERROR").lowCardinalityKeyValue("replayed", "false");
            observation.error(ex);
            throw ex;
        } finally {
            observation.stop();
        }
    }

    private static void tag(Observation observation, PaymentResponse payment, boolean replayed) {
        observation.lowCardinalityKeyValue("status", payment.status().name())
                .lowCardinalityKeyValue("replayed", String.valueOf(replayed))
                .highCardinalityKeyValue("payment.id", String.valueOf(payment.id()));   // span only, never a metric tag
    }

    private TransferResult execute(String payerId, String idempotencyKey, TransferRequest request) {
        String requestHash = fingerprint(request);
        if (idempotencyKey != null) {
            Optional<Payment> earlier = repository.findByPayerIdAndIdempotencyKey(payerId, idempotencyKey);
            if (earlier.isPresent()) {
                return replay(earlier.get(), idempotencyKey, requestHash);
            }
        }

        // Record the intent FIRST. If anything later crashes, the recovery job finds it.
        Payment payment = new Payment(payerId, request.sourceAccountId(), request.targetAccountNumber(),
                request.amount(), request.description(), idempotencyKey, requestHash);
        payment.scheduleRecovery(Instant.now().plus(recovery.gracePeriod()));
        try {
            payment = store.save(payment);
        } catch (DataIntegrityViolationException duplicate) {
            // The same key arrived twice at the same moment (double click, client retry racing
            // the original). The unique constraint let exactly one request create the payment.
            if (idempotencyKey == null) {
                throw duplicate;
            }
            Payment winner = repository.findByPayerIdAndIdempotencyKey(payerId, idempotencyKey).orElseThrow(() -> duplicate);
            return replay(winner, idempotencyKey, requestHash);
        }

        payment = saga.start(payment);

        audit.info("payment={} payer={} {} -> {} amount={} {} status={} code={}",
                payment.getId(), payerId, payment.getSourceAccountId(), payment.getTargetAccountNumber(),
                payment.getAmount(), payment.getCurrency(), payment.getStatus(), payment.getFailureCode());
        return outcome(payment, false);
    }

    private TransferResult replay(Payment earlier, String key, String requestHash) {
        if (!requestHash.equals(earlier.getRequestHash())) {
            throw new IdempotencyKeyReusedException(key);
        }
        audit.info("payment={} payer={} replayed for Idempotency-Key={} status={}",
                earlier.getId(), earlier.getPayerId(), key, earlier.getStatus());
        return outcome(earlier, true);
    }

    /** The same mapping for a first answer and for a replay: the client sees the same outcome both times. */
    private static TransferResult outcome(Payment payment, boolean replayed) {
        PaymentResponse response = PaymentMapper.toResponse(payment);
        return switch (payment.getStatus()) {
            case COMPLETED, PENDING, DEBITED, COMPENSATING, NEEDS_RECONCILIATION -> new TransferResult(response, replayed);
            case FAILED -> throw new PaymentNotCompletedException(response, true, replayed);   // nothing moved: retry later
            case REJECTED, REVERSED -> throw new PaymentNotCompletedException(response, false, replayed);
        };
    }

    /**
     * What makes two requests "the same": every field that changes what happens. SHA-256 so the
     * stored value has a fixed size and reveals nothing new (the payment row has the fields anyway).
     */
    static String fingerprint(TransferRequest request) {
        String canonical = String.join("|",
                request.sourceAccountId().toString(),
                request.targetAccountNumber(),
                request.amount().stripTrailingZeros().toPlainString(),   // 10, 10.0 and 10.00 are the same amount
                request.description() == null ? "" : request.description());
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is always available", ex);
        }
    }

    // ------------------------------------------------------------------ queries

    @Transactional(readOnly = true)
    public Page<PaymentResponse> listOwnedBy(String payerId, Pageable pageable) {
        return repository.findByPayerId(payerId, pageable).map(PaymentMapper::toResponse);
    }

    /** 404 for other customers' payments, exactly like for unknown IDs. */
    @Transactional(readOnly = true)
    public PaymentResponse getOwned(UUID id, String payerId) {
        return repository.findByIdAndPayerId(id, payerId).map(PaymentMapper::toResponse)
                .orElseThrow(() -> new ResourceNotFoundException("Payment '%s' not found".formatted(id)));
    }

    @Transactional(readOnly = true)
    public Page<PaymentResponse> list(PaymentStatus status, Pageable pageable) {
        Page<Payment> page = status == null ? repository.findAll(pageable) : repository.findByStatus(status, pageable);
        return page.map(PaymentMapper::toResponse);
    }

    @Transactional(readOnly = true)
    public PaymentResponse getById(UUID id) {
        return repository.findById(id).map(PaymentMapper::toResponse)
                .orElseThrow(() -> new ResourceNotFoundException("Payment '%s' not found".formatted(id)));
    }
}
