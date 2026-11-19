package com.banking.paymentservice.service;

import com.banking.paymentservice.client.AccountRejectedException;
import com.banking.paymentservice.client.AccountServiceClient;
import com.banking.paymentservice.client.AccountServiceUnavailableException;
import com.banking.paymentservice.client.AccountView;
import com.banking.paymentservice.dto.PaymentResponse;
import com.banking.paymentservice.dto.TransferRequest;
import com.banking.paymentservice.entity.Payment;
import com.banking.paymentservice.entity.PaymentStatus;
import com.banking.paymentservice.exception.PaymentNotCompletedException;
import com.banking.paymentservice.exception.ResourceNotFoundException;
import com.banking.paymentservice.mapper.PaymentMapper;
import com.banking.paymentservice.repository.PaymentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Orchestrates a transfer: validate both accounts, debit the source, credit the target.
 *
 * <p>Why this class is NOT {@code @Transactional}: the money moves in ANOTHER service's
 * database. No local transaction can make "debit in account_db + credit in account_db +
 * record in payment_db" atomic. Holding a DB transaction open while waiting on HTTP would
 * only tie up a connection and lock rows for nothing. So each step is saved on its own
 * ({@code repository.save} runs in its own short transaction), and the payment record tells
 * exactly how far we got.
 *
 * <p>What happens when a step fails is the core lesson of Phase 9; see {@link PaymentStatus}.
 * The fix for "we don't know if it happened" is idempotency + a saga (Phase 13).
 */
@Service
public class PaymentService {

    static final String ACCOUNT_SERVICE_UNAVAILABLE = "ACCOUNT_SERVICE_UNAVAILABLE";

    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);
    private static final Logger audit = LoggerFactory.getLogger("AUDIT");

    private final PaymentRepository repository;
    private final AccountServiceClient accounts;

    public PaymentService(PaymentRepository repository, AccountServiceClient accounts) {
        this.repository = repository;
        this.accounts = accounts;
    }

    /**
     * @return the payment when it COMPLETED or NEEDS_RECONCILIATION (the request was accepted,
     *         but its outcome is still being determined)
     * @throws PaymentNotCompletedException when the transfer did not happen (REJECTED, FAILED,
     *         REVERSED). The payment is still recorded, and the exception carries it.
     */
    public PaymentResponse transfer(String payerId, TransferRequest request) {
        // Record the intent FIRST. If anything later crashes, there is a trace to investigate.
        Payment payment = repository.save(new Payment(payerId, request.sourceAccountId(),
                request.targetAccountId(), request.amount(), request.description()));

        payment = execute(payment);

        audit.info("payment={} payer={} {} -> {} amount={} {} status={} code={}",
                payment.getId(), payerId, payment.getSourceAccountId(), payment.getTargetAccountId(),
                payment.getAmount(), payment.getCurrency(), payment.getStatus(), payment.getFailureCode());

        PaymentResponse response = PaymentMapper.toResponse(payment);
        if (payment.getStatus() == PaymentStatus.COMPLETED || payment.getStatus() == PaymentStatus.NEEDS_RECONCILIATION) {
            return response;
        }
        throw new PaymentNotCompletedException(response,
                ACCOUNT_SERVICE_UNAVAILABLE.equals(payment.getFailureCode()));
    }

    private Payment execute(Payment payment) {
        // ---- Step 1: validate (reads only; nothing can go half-wrong yet) -------------------
        AccountView source;
        AccountView target;
        try {
            source = accounts.getAccount(payment.getSourceAccountId());
        } catch (AccountRejectedException ex) {
            // 404 also means "not yours": account-service checked ownership with the relayed token.
            return reject(payment, ex.isNotFound() ? "SOURCE_ACCOUNT_NOT_FOUND" : ex.getCode(), ex.getMessage());
        } catch (AccountServiceUnavailableException ex) {
            return fail(payment, ex);
        }
        try {
            target = accounts.getAccount(payment.getTargetAccountId());
        } catch (AccountRejectedException ex) {
            // With token relay, "not found" includes OTHER customers' accounts. See Phase 10.
            return reject(payment, ex.isNotFound() ? "TARGET_ACCOUNT_NOT_FOUND" : ex.getCode(), ex.getMessage());
        } catch (AccountServiceUnavailableException ex) {
            return fail(payment, ex);
        }

        if (!source.currency().equals(target.currency())) {
            return reject(payment, "CURRENCY_MISMATCH", "Source account is in %s, target account is in %s"
                    .formatted(source.currency(), target.currency()));
        }
        if (!target.isActive()) {
            // Checked up front because a failing credit would force a refund later.
            // The source's status and balance are NOT pre-checked: the withdraw itself enforces them
            // atomically in account-service, which is the only reliable place to do it.
            return reject(payment, "TARGET_ACCOUNT_NOT_ACTIVE", "Target account is " + target.status());
        }
        payment.currencyConfirmed(source.currency());

        // ---- Step 2: debit the source ---------------------------------------------------------
        try {
            accounts.debit(payment.getSourceAccountId(), payment.getAmount());
        } catch (AccountRejectedException ex) {
            return reject(payment, ex.getCode(), ex.getMessage());       // e.g. INSUFFICIENT_FUNDS
        } catch (AccountServiceUnavailableException ex) {
            if (ex.isOutcomeUnknown()) {
                // Timeout or 5xx after the request was sent: the money may already be gone.
                // Retrying blindly could debit twice, giving up could lose the debit. Escalate.
                return reconcile(payment, "DEBIT_OUTCOME_UNKNOWN", ex);
            }
            return fail(payment, ex);
        }
        payment.markDebited();
        payment = repository.save(payment);   // the "money is in flight" checkpoint

        // ---- Step 3: credit the target --------------------------------------------------------
        try {
            accounts.credit(payment.getTargetAccountId(), payment.getAmount());
            payment.complete();
            return repository.save(payment);
        } catch (AccountServiceUnavailableException ex) {
            if (ex.isOutcomeUnknown()) {
                // The credit might have happened. Refunding now could pay the amount out twice.
                return reconcile(payment, "CREDIT_OUTCOME_UNKNOWN", ex);
            }
            return compensate(payment, ACCOUNT_SERVICE_UNAVAILABLE, ex.getMessage());
        } catch (AccountRejectedException ex) {
            // e.g. the target was frozen between our check and the credit.
            return compensate(payment, ex.getCode(), ex.getMessage());
        }
    }

    /**
     * The credit certainly failed after the debit succeeded: give the money back to the source.
     * This compensating action is the seed of the Saga pattern (Phase 13).
     */
    private Payment compensate(Payment payment, String creditFailureCode, String creditFailure) {
        try {
            accounts.credit(payment.getSourceAccountId(), payment.getAmount());
            payment.reverse(creditFailureCode,
                    "Credit to target failed (%s); the amount was returned to the source account".formatted(creditFailure));
            return repository.save(payment);
        } catch (AccountRejectedException | AccountServiceUnavailableException refundFailure) {
            // Worst case: debited, not credited, not refunded. Only a human (or Phase 13) can fix it.
            log.error("Refund failed for payment {}: money debited but neither credited nor returned",
                    payment.getId(), refundFailure);
            payment.requireReconciliation("REFUND_FAILED",
                    "Credit failed (%s) and the refund failed (%s)".formatted(creditFailure, refundFailure.getMessage()));
            return repository.save(payment);
        }
    }

    private Payment reject(Payment payment, String code, String reason) {
        payment.reject(code, reason);
        return repository.save(payment);
    }

    private Payment fail(Payment payment, AccountServiceUnavailableException ex) {
        log.warn("Payment {} failed, nothing was moved: {}", payment.getId(), ex.getMessage());
        payment.fail(ACCOUNT_SERVICE_UNAVAILABLE, ex.getMessage());
        return repository.save(payment);
    }

    private Payment reconcile(Payment payment, String code, AccountServiceUnavailableException ex) {
        log.error("Payment {} needs reconciliation ({}): {}", payment.getId(), code, ex.getMessage(), ex);
        payment.requireReconciliation(code, ex.getMessage());
        return repository.save(payment);
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
