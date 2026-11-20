package com.banking.accountservice.service;

import com.banking.accountservice.dto.AccountResponse;
import com.banking.accountservice.dto.AccountSummary;
import com.banking.accountservice.dto.BalanceResponse;
import com.banking.accountservice.entity.Account;
import com.banking.accountservice.entity.AccountStatus;
import com.banking.accountservice.entity.AccountType;
import com.banking.accountservice.exception.ResourceNotFoundException;
import com.banking.accountservice.exception.UnsupportedCurrencyException;
import com.banking.accountservice.mapper.AccountMapper;
import com.banking.accountservice.repository.AccountRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.banking.accountservice.events.AccountTransactionEvent;
import com.banking.accountservice.events.TransactionKind;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Currency;
import java.util.List;
import java.util.UUID;

/**
 * Account use cases, split by WHO is acting:
 * <ul>
 *   <li><b>Owner operations</b> take the caller's {@code ownerId} and only ever touch accounts
 *       that belong to it. Ownership is enforced HERE (not only in the controller), so no
 *       future endpoint can forget it.</li>
 *   <li><b>Back-office operations</b> work on any account. Role checks are done by the
 *       controllers, and every change is written to the audit log.</li>
 * </ul>
 */
@Service
@Transactional(readOnly = true)
public class AccountService {

    /** A dedicated logger for "who did what": easy to route to an audit store later (Phase 14). */
    private static final Logger audit = LoggerFactory.getLogger("AUDIT");
    private static final String RESOURCE = "Account";
    private static final int MAX_ACCOUNT_NUMBER_ATTEMPTS = 5;

    private final AccountRepository accountRepository;
    private final AccountNumberGenerator accountNumberGenerator;
    private final AccountMapper accountMapper;
    private final ApplicationEventPublisher events;

    public AccountService(AccountRepository accountRepository,
                          AccountNumberGenerator accountNumberGenerator,
                          AccountMapper accountMapper,
                          ApplicationEventPublisher events) {
        this.accountRepository = accountRepository;
        this.accountNumberGenerator = accountNumberGenerator;
        this.accountMapper = accountMapper;
        this.events = events;
    }

    // ---------------------------------------------------------------- owner operations

    @Transactional
    public AccountResponse open(String ownerId, AccountType type, String currencyCode, String actorId) {
        String currency = requireSupportedCurrency(currencyCode);
        Account account = accountRepository.saveAndFlush(
                new Account(uniqueAccountNumber(), ownerId, type, currency));
        audit.info("action=OPEN_ACCOUNT actor={} owner={} account={} type={} currency={}",
                actorId, ownerId, account.getId(), type, currency);
        return accountMapper.toResponse(account);
    }

    public List<AccountResponse> listOwnedBy(String ownerId) {
        return accountRepository.findByOwnerIdOrderByCreatedAtAsc(ownerId).stream()
                .map(accountMapper::toResponse)
                .toList();
    }

    public AccountResponse getOwned(UUID id, String ownerId) {
        return accountMapper.toResponse(findOwnedOrThrow(id, ownerId));
    }

    public BalanceResponse getOwnedBalance(UUID id, String ownerId) {
        return accountMapper.toBalance(findOwnedOrThrow(id, ownerId));
    }

    /**
     * Known gap, fixed in Phase 13: a client retrying a timed-out deposit would deposit twice.
     * That's why payment requests get an Idempotency-Key.
     */
    @Transactional
    public AccountResponse deposit(UUID id, String ownerId, BigDecimal amount) {
        Account account = findOwnedOrThrow(id, ownerId);
        account.deposit(amount);
        accountRepository.flush();   // surfaces optimistic-lock conflicts here, mapped to 409
        audit.info("action=DEPOSIT actor={} account={} amount={}", ownerId, id, amount);
        recordTransaction(account, TransactionKind.DEPOSIT, "CREDIT", amount, null);
        return accountMapper.toResponse(account);
    }

    @Transactional
    public AccountResponse withdraw(UUID id, String ownerId, BigDecimal amount) {
        return withdraw(id, ownerId, amount, null);
    }

    /** @param paymentId set when payment-service debits for a transfer (with the owner's token) */
    @Transactional
    public AccountResponse withdraw(UUID id, String ownerId, BigDecimal amount, UUID paymentId) {
        Account account = findOwnedOrThrow(id, ownerId);
        account.withdraw(amount);
        accountRepository.flush();
        audit.info("action=WITHDRAW actor={} account={} amount={} payment={}", ownerId, id, amount, paymentId);
        recordTransaction(account, paymentId == null ? TransactionKind.WITHDRAWAL : TransactionKind.TRANSFER_OUT,
                "DEBIT", amount, paymentId);
        return accountMapper.toResponse(account);
    }

    // ---------------------------------------------------------------- machine-to-machine (Phase 10)

    /** For payment-service: is this a valid recipient? No owner or balance is revealed. */
    @Transactional(readOnly = true)
    public AccountSummary findRecipient(String accountNumber) {
        return accountRepository.findByAccountNumber(accountNumber).map(accountMapper::toSummary)
                .orElseThrow(() -> new ResourceNotFoundException("Account number '%s' not found".formatted(accountNumber)));
    }

    /**
     * Credit ANY active account: the one capability payment-service needs that no customer has.
     * The payment ID links this ledger entry to the payment that caused it (idempotency: Phase 13).
     */
    @Transactional
    public AccountSummary creditForPayment(UUID id, BigDecimal amount, UUID paymentId, boolean refund, String clientId) {
        Account account = findOrThrow(id);
        account.deposit(amount);
        accountRepository.flush();
        audit.info("action={} actor=client:{} account={} amount={} payment={}",
                refund ? "REFUND" : "CREDIT", clientId, id, amount, paymentId);
        recordTransaction(account, refund ? TransactionKind.REFUND : TransactionKind.TRANSFER_IN, "CREDIT", amount, paymentId);
        return accountMapper.toSummary(account);
    }

    // ---------------------------------------------------------------- back-office operations

    public AccountResponse getById(UUID id) {
        return accountMapper.toResponse(findOrThrow(id));
    }

    @Transactional
    public AccountResponse changeStatus(UUID id, AccountStatus status, String reason, String actorId) {
        Account account = findOrThrow(id);
        AccountStatus previous = account.getStatus();
        account.changeStatus(status);
        accountRepository.flush();
        audit.info("action=CHANGE_STATUS actor={} account={} from={} to={} reason=\"{}\"",
                actorId, id, previous, status, reason == null ? "" : reason.replace('"', '\''));
        return accountMapper.toResponse(account);
    }

    // ---------------------------------------------------------------- helpers

    /**
     * Raises an in-process event. AccountEventPublisher sends it to Kafka only once this
     * transaction has COMMITTED; on rollback it is silently dropped.
     */
    private void recordTransaction(Account account, TransactionKind kind, String direction,
                                   BigDecimal amount, UUID paymentId) {
        events.publishEvent(new AccountTransactionEvent(UUID.randomUUID(), AccountTransactionEvent.TYPE,
                AccountTransactionEvent.VERSION, Instant.now(), account.getId(), account.getAccountNumber(),
                account.getOwnerId(), kind, direction, amount.setScale(Account.MONEY_SCALE),
                account.getCurrency(), account.getBalance(), paymentId));
    }

    private Account findOwnedOrThrow(UUID id, String ownerId) {
        return accountRepository.findByIdAndOwnerId(id, ownerId)
                .orElseThrow(() -> new ResourceNotFoundException(RESOURCE, id));
    }

    private Account findOrThrow(UUID id) {
        return accountRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException(RESOURCE, id));
    }

    /** The DB unique constraint is the final guarantee. This loop just makes a collision very unlikely to reach it. */
    private String uniqueAccountNumber() {
        for (int attempt = 0; attempt < MAX_ACCOUNT_NUMBER_ATTEMPTS; attempt++) {
            String candidate = accountNumberGenerator.next();
            if (!accountRepository.existsByAccountNumber(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException("Could not generate a unique account number");
    }

    private static String requireSupportedCurrency(String code) {
        try {
            return Currency.getInstance(code).getCurrencyCode();
        } catch (IllegalArgumentException ex) {
            throw new UnsupportedCurrencyException(code);
        }
    }
}
