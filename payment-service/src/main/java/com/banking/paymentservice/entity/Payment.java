package com.banking.paymentservice.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * One transfer request and what happened to it.
 *
 * <p>payment-service never holds money. Balances live only in account-service. This record is
 * the payment's own history: who asked, what was asked, and how far the transfer got. That's
 * exactly what you need when a distributed operation stops halfway.
 */
@Entity
@Table(name = "payments")
public class Payment {

    private static final int MAX_REASON_LENGTH = 500;

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /** Keycloak {@code sub} of the customer who requested the transfer. */
    @Column(nullable = false, updatable = false, length = 36)
    private String payerId;

    /** IDs in account-service. No foreign keys: that data lives in another database. */
    @Column(nullable = false, updatable = false)
    private UUID sourceAccountId;

    /** account-service ID of the recipient; null until account-service resolved the number. */
    @Column
    private UUID targetAccountId;

    /** What the customer typed: the recipient's account number (may belong to another customer). */
    @Column(updatable = false, length = 10)
    private String targetAccountNumber;

    @Column(nullable = false, updatable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    /** Known only after account-service has told us the source account's currency. */
    @Column(length = 3)
    private String currency;

    @Column(length = 140)
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private PaymentStatus status;

    /** Machine-readable reason, e.g. INSUFFICIENT_FUNDS (often passed through from account-service). */
    @Column(length = 50)
    private String failureCode;

    @Column(length = MAX_REASON_LENGTH)
    private String failureReason;

    /** Phase 13: the client's Idempotency-Key (unique per payer). null if the client sent none. */
    @Column(updatable = false, length = 100)
    private String idempotencyKey;

    /** SHA-256 of the request: the same key with a DIFFERENT request is refused. */
    @Column(updatable = false, length = 64)
    private String requestHash;

    /** When the recovery job may next work on this payment. null once the payment is final. */
    private Instant nextRecoveryAt;

    @Column(nullable = false)
    private int recoveryAttempts;

    @Version
    private long version;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(nullable = false)
    private Instant updatedAt;

    protected Payment() {
        // for JPA
    }

    public Payment(String payerId, UUID sourceAccountId, String targetAccountNumber, BigDecimal amount, String description) {
        this(payerId, sourceAccountId, targetAccountNumber, amount, description, null, null);
    }

    public Payment(String payerId, UUID sourceAccountId, String targetAccountNumber, BigDecimal amount, String description,
                   String idempotencyKey, String requestHash) {
        this.idempotencyKey = idempotencyKey;
        this.requestHash = requestHash;
        this.payerId = Objects.requireNonNull(payerId);
        this.sourceAccountId = Objects.requireNonNull(sourceAccountId);
        this.targetAccountNumber = Objects.requireNonNull(targetAccountNumber);
        // Always 2 decimals ("120" → 120.00), the same scale as the database column.
        // UNNECESSARY: a 3rd decimal must have been rejected by validation, never silently rounded.
        this.amount = Objects.requireNonNull(amount).setScale(2, RoundingMode.UNNECESSARY);
        this.description = description;
        this.status = PaymentStatus.PENDING;
    }

    // ------------------------------------------------------------------ state transitions

    /** account-service confirmed both accounts: we now know the recipient's ID and the currency. */
    public void accountsResolved(UUID targetAccountId, String currency) {
        if (status != PaymentStatus.PENDING) {
            throw new IllegalStateException("Accounts can only be resolved while PENDING");
        }
        if (sourceAccountId.equals(targetAccountId)) {
            throw new IllegalArgumentException("Source and target account must differ");
        }
        this.targetAccountId = Objects.requireNonNull(targetAccountId);
        this.currency = Objects.requireNonNull(currency);
    }

    public void markDebited() {
        moveTo(PaymentStatus.DEBITED, null, null);
    }

    public void complete() {
        moveTo(PaymentStatus.COMPLETED, null, null);
    }

    public void reject(String code, String reason) {
        moveTo(PaymentStatus.REJECTED, code, reason);
    }

    public void fail(String code, String reason) {
        moveTo(PaymentStatus.FAILED, code, reason);
    }

    /** The credit was refused after the debit: the money must go back. Keeps the credit's failure reason. */
    public void startCompensation(String creditFailureCode, String creditFailure) {
        moveTo(PaymentStatus.COMPENSATING, creditFailureCode, "Credit to target failed (%s)".formatted(creditFailure));
    }

    /** The refund succeeded. The failure code/reason still explain WHY the transfer was reversed. */
    public void reverse() {
        moveTo(PaymentStatus.REVERSED, failureCode, failureReason + "; the amount was returned to the source account");
    }

    /** Something went wrong but the saga isn't over (e.g. CREDIT_OUTCOME_UNKNOWN): visible to support and the customer. */
    public void noteIssue(String code, String reason) {
        if (status.isFinal()) {
            throw new IllegalStateException("Payment %s is already final".formatted(id));
        }
        this.failureCode = code;
        this.failureReason = truncate(reason);
    }

    // ------------------------------------------------------------------ recovery (Phase 13)

    /** The live request has until {@code at} to finish; after that the recovery job may take over. */
    public void scheduleRecovery(Instant at) {
        this.nextRecoveryAt = at;
    }

    /** Claimed by the recovery job: count the attempt and set when the next one may happen. */
    public void recoveryAttempted(Instant nextAttemptAt) {
        if (status.isFinal()) {
            throw new IllegalStateException("Payment %s is already final".formatted(id));
        }
        this.recoveryAttempts++;
        this.nextRecoveryAt = nextAttemptAt;
    }

    public void requireReconciliation(String code, String reason) {
        moveTo(PaymentStatus.NEEDS_RECONCILIATION, code, reason);
    }

    private void moveTo(PaymentStatus next, String code, String reason) {
        if (!status.canMoveTo(next)) {
            throw new IllegalStateException("Payment %s cannot move from %s to %s".formatted(id, status, next));
        }
        this.status = next;
        this.failureCode = code;
        this.failureReason = truncate(reason);
        if (next.isFinal()) {
            this.nextRecoveryAt = null;   // nothing left to recover
        }
    }

    private static String truncate(String text) {
        return text == null || text.length() <= MAX_REASON_LENGTH ? text : text.substring(0, MAX_REASON_LENGTH);
    }

    // ------------------------------------------------------------------ getters

    public UUID getId() {
        return id;
    }

    public String getPayerId() {
        return payerId;
    }

    public UUID getSourceAccountId() {
        return sourceAccountId;
    }

    public UUID getTargetAccountId() {
        return targetAccountId;
    }

    public String getTargetAccountNumber() {
        return targetAccountNumber;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public String getCurrency() {
        return currency;
    }

    public String getDescription() {
        return description;
    }

    public PaymentStatus getStatus() {
        return status;
    }

    public String getFailureCode() {
        return failureCode;
    }

    public String getFailureReason() {
        return failureReason;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public String getRequestHash() {
        return requestHash;
    }

    public Instant getNextRecoveryAt() {
        return nextRecoveryAt;
    }

    public int getRecoveryAttempts() {
        return recoveryAttempts;
    }

    public long getVersion() {
        return version;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
