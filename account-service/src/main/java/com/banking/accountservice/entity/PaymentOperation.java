package com.banking.accountservice.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import org.springframework.data.domain.Persistable;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * "Payment X already did Y here": the idempotency record for payment-driven balance changes.
 * Written in the same transaction as the balance change itself, so the two can't disagree.
 */
@Entity
@Table(name = "payment_operations")
public class PaymentOperation implements Persistable<PaymentOperation.Key> {

    @EmbeddedId
    private Key key;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 20)
    private PaymentOperationKind kind;

    /** null for VOID. */
    @Column(updatable = false)
    private UUID accountId;

    @Column(updatable = false, precision = 19, scale = Account.MONEY_SCALE)
    private BigDecimal amount;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    protected PaymentOperation() {
        // for JPA
    }

    private PaymentOperation(UUID paymentId, PaymentOperationKind kind, UUID accountId, BigDecimal amount) {
        this.key = new Key(paymentId, kind.leg());
        this.kind = kind;
        this.accountId = accountId;
        this.amount = amount;
        this.createdAt = Instant.now();
    }

    public static PaymentOperation moneyMoved(UUID paymentId, PaymentOperationKind kind, UUID accountId, BigDecimal amount) {
        if (kind == PaymentOperationKind.VOID) {
            throw new IllegalArgumentException("A VOID moves no money");
        }
        return new PaymentOperation(paymentId, kind, Objects.requireNonNull(accountId), Objects.requireNonNull(amount));
    }

    public static PaymentOperation voidedDebit(UUID paymentId) {
        return new PaymentOperation(paymentId, PaymentOperationKind.VOID, null, null);
    }

    /** Same payment, same kind, same account, same amount: a repeat of THIS operation. */
    public boolean isSameAs(PaymentOperationKind kind, UUID accountId, BigDecimal amount) {
        return this.kind == kind && Objects.equals(this.accountId, accountId)
                && this.amount != null && this.amount.compareTo(amount) == 0;
    }

    /** Always new: rows are never updated, so Spring Data can INSERT without a SELECT first. */
    @Override
    public boolean isNew() {
        return true;
    }

    @Override
    public Key getId() {
        return key;
    }

    public UUID getPaymentId() {
        return key.paymentId;
    }

    public PaymentOperationKind getKind() {
        return kind;
    }

    public UUID getAccountId() {
        return accountId;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    @Embeddable
    public static class Key implements Serializable {

        @Column(name = "payment_id", nullable = false, updatable = false)
        private UUID paymentId;

        @Enumerated(EnumType.STRING)
        @Column(name = "leg", nullable = false, updatable = false, length = 20)
        private PaymentLeg leg;

        protected Key() {
            // for JPA
        }

        public Key(UUID paymentId, PaymentLeg leg) {
            this.paymentId = Objects.requireNonNull(paymentId);
            this.leg = Objects.requireNonNull(leg);
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Key other && paymentId.equals(other.paymentId) && leg == other.leg;
        }

        @Override
        public int hashCode() {
            return Objects.hash(paymentId, leg);
        }
    }
}
