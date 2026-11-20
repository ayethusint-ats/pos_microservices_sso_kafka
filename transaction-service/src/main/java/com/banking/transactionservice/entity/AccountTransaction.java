package com.banking.transactionservice.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import org.hibernate.annotations.CreationTimestamp;
import org.springframework.data.domain.Persistable;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * One line of a customer's statement: a READ MODEL (projection) built from account-service events.
 *
 * <p>The primary key is the event's ID. Kafka delivers at least once, so the same event can
 * arrive twice (consumer crash before the offset commit, rebalance...). A second insert with the
 * same key is rejected by the database: idempotency by construction.
 *
 * <p>This table can always be thrown away and rebuilt by replaying the topic from the beginning.
 */
@Entity
@Table(name = "account_transactions")
public class AccountTransaction implements Persistable<UUID> {

    @Id
    private UUID id;

    @Column(nullable = false, updatable = false)
    private UUID accountId;

    @Column(nullable = false, updatable = false, length = 10)
    private String accountNumber;

    @Column(nullable = false, updatable = false, length = 36)
    private String ownerId;

    @Column(nullable = false, updatable = false, length = 20)
    private String kind;

    @Column(nullable = false, updatable = false, length = 6)
    private String direction;

    @Column(nullable = false, updatable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    @Column(nullable = false, updatable = false, length = 3)
    private String currency;

    @Column(nullable = false, updatable = false, precision = 19, scale = 2)
    private BigDecimal balanceAfter;

    @Column(updatable = false)
    private UUID paymentId;

    /** When the money moved (from the event), not when we heard about it. */
    @Column(nullable = false, updatable = false)
    private Instant occurredAt;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private Instant recordedAt;

    /** The ID is assigned (event ID), so tell Spring Data "this is new": INSERT, not SELECT + merge. */
    @Transient
    private boolean isNew = true;

    protected AccountTransaction() {
        // for JPA
    }

    public AccountTransaction(UUID eventId, UUID accountId, String accountNumber, String ownerId, String kind,
                              String direction, BigDecimal amount, String currency, BigDecimal balanceAfter,
                              UUID paymentId, Instant occurredAt) {
        this.id = eventId;
        this.accountId = accountId;
        this.accountNumber = accountNumber;
        this.ownerId = ownerId;
        this.kind = kind;
        this.direction = direction;
        this.amount = amount;
        this.currency = currency;
        this.balanceAfter = balanceAfter;
        this.paymentId = paymentId;
        this.occurredAt = occurredAt;
    }

    @Override
    public UUID getId() {
        return id;
    }

    @Override
    public boolean isNew() {
        return isNew;
    }

    public UUID getAccountId() {
        return accountId;
    }

    public String getAccountNumber() {
        return accountNumber;
    }

    public String getOwnerId() {
        return ownerId;
    }

    public String getKind() {
        return kind;
    }

    public String getDirection() {
        return direction;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public String getCurrency() {
        return currency;
    }

    public BigDecimal getBalanceAfter() {
        return balanceAfter;
    }

    public UUID getPaymentId() {
        return paymentId;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }

    public Instant getRecordedAt() {
        return recordedAt;
    }
}
