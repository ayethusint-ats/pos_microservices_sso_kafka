package com.banking.accountservice.entity;

import com.banking.accountservice.exception.AccountNotActiveException;
import com.banking.accountservice.exception.AccountNotEmptyException;
import com.banking.accountservice.exception.InvalidStatusTransitionException;
import com.banking.accountservice.exception.InsufficientFundsException;
import com.banking.accountservice.exception.InvalidAmountException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.UUID;

/**
 * A bank account. This is the only place in the whole platform where a balance changes.
 *
 * <p>Rich domain model: the money rules (positive amounts, no overdraft, account must be
 * active) live inside the entity, so no caller can put it into an invalid state.
 */
@Entity
@Table(name = "accounts",
        uniqueConstraints = @UniqueConstraint(name = "uk_accounts_account_number", columnNames = "account_number"),
        indexes = @Index(name = "idx_accounts_owner_id", columnList = "owner_id"))
public class Account {

    /** Money is stored with 2 decimal places. Never use double/float for money. */
    public static final int MONEY_SCALE = 2;

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, updatable = false, length = 10)
    private String accountNumber;

    /** Keycloak user ID (JWT "sub") of the owner, so ownership checks need no call to user-service. */
    @Column(nullable = false, updatable = false, length = 36)
    private String ownerId;

    @Enumerated(EnumType.STRING)   // store "SAVINGS", not 1: reordering the enum can't corrupt data
    @Column(nullable = false, length = 20)
    private AccountType type;

    /** ISO 4217 code. An account holds exactly one currency for its whole life. */
    @Column(nullable = false, updatable = false, length = 3)
    private String currency;

    @Column(nullable = false, precision = 19, scale = MONEY_SCALE)
    private BigDecimal balance;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private AccountStatus status;

    /**
     * Optimistic locking. Every UPDATE runs "... WHERE id = ? AND version = ?".
     * If two withdrawals read the same balance concurrently, the second update finds no row
     * with the old version and fails, instead of silently overwriting the first ("lost update").
     */
    @Version
    private long version;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(nullable = false)
    private Instant updatedAt;

    protected Account() {
        // required by JPA
    }

    public Account(String accountNumber, String ownerId, AccountType type, String currency) {
        this.accountNumber = accountNumber;
        this.ownerId = ownerId;
        this.type = type;
        this.currency = currency;
        this.balance = BigDecimal.ZERO.setScale(MONEY_SCALE);
        this.status = AccountStatus.ACTIVE;
    }

    public void deposit(BigDecimal amount) {
        BigDecimal value = requireValidAmount(amount);
        requireActive();
        balance = balance.add(value);
    }

    public void withdraw(BigDecimal amount) {
        BigDecimal value = requireValidAmount(amount);
        requireActive();
        if (balance.compareTo(value) < 0) {
            throw new InsufficientFundsException(id, balance, value);
        }
        balance = balance.subtract(value);
    }

    /**
     * Account lifecycle, managed by ADMIN:
     * <pre>
     *   ACTIVE ⇄ FROZEN        (freeze: e.g. suspected fraud; no money moves while frozen)
     *   ACTIVE/FROZEN → CLOSED (only with a zero balance; CLOSED is final)
     * </pre>
     * Setting the current status again is a no-op (idempotent).
     */
    public void changeStatus(AccountStatus target) {
        if (target == null || target == status) {
            return;
        }
        if (status == AccountStatus.CLOSED) {
            throw new InvalidStatusTransitionException(id, status, target);
        }
        if (target == AccountStatus.CLOSED && balance.signum() != 0) {
            throw new AccountNotEmptyException(id, balance);
        }
        status = target;
    }

    private void requireActive() {
        if (status != AccountStatus.ACTIVE) {
            throw new AccountNotActiveException(id, status);
        }
    }

    /** Defense in depth: the DTO is validated too, but the entity must protect itself from any caller. */
    private static BigDecimal requireValidAmount(BigDecimal amount) {
        if (amount == null || amount.signum() <= 0) {
            throw new InvalidAmountException("Amount must be greater than zero");
        }
        if (amount.stripTrailingZeros().scale() > MONEY_SCALE) {
            throw new InvalidAmountException("Amount must have at most " + MONEY_SCALE + " decimal places");
        }
        return amount.setScale(MONEY_SCALE, RoundingMode.UNNECESSARY);
    }

    public UUID getId() {
        return id;
    }

    public String getAccountNumber() {
        return accountNumber;
    }

    public String getOwnerId() {
        return ownerId;
    }

    public AccountType getType() {
        return type;
    }

    public String getCurrency() {
        return currency;
    }

    public BigDecimal getBalance() {
        return balance;
    }

    public AccountStatus getStatus() {
        return status;
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
