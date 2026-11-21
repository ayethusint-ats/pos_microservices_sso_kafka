package com.banking.accountservice.service;

import com.banking.accountservice.dto.AccountResponse;
import com.banking.accountservice.dto.AccountSummary;
import com.banking.accountservice.dto.BalanceResponse;
import com.banking.accountservice.dto.DebitResolution;
import com.banking.accountservice.entity.Account;
import com.banking.accountservice.entity.AccountStatus;
import com.banking.accountservice.entity.AccountType;
import com.banking.accountservice.entity.PaymentLeg;
import com.banking.accountservice.entity.PaymentOperation;
import com.banking.accountservice.entity.PaymentOperationKind;
import com.banking.accountservice.events.AccountEventPublisher;
import com.banking.accountservice.events.AccountTransactionEvent;
import com.banking.accountservice.events.TransactionKind;
import com.banking.accountservice.exception.PaymentOperationConflictException;
import com.banking.accountservice.exception.ResourceNotFoundException;
import com.banking.accountservice.exception.UnsupportedCurrencyException;
import com.banking.accountservice.mapper.AccountMapper;
import com.banking.accountservice.repository.AccountRepository;
import com.banking.accountservice.repository.PaymentOperationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Currency;
import java.util.List;
import java.util.Optional;
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
    private final PaymentOperationRepository operations;
    private final AccountEventPublisher events;

    public AccountService(AccountRepository accountRepository,
                          AccountNumberGenerator accountNumberGenerator,
                          AccountMapper accountMapper,
                          PaymentOperationRepository operations,
                          AccountEventPublisher events) {
        this.accountRepository = accountRepository;
        this.accountNumberGenerator = accountNumberGenerator;
        this.accountMapper = accountMapper;
        this.operations = operations;
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
     * Not idempotent: a customer retrying a timed-out deposit deposits twice. Money between
     * customers moves through payments, which ARE idempotent (payment ID / Idempotency-Key).
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

    /**
     * @param paymentId set when payment-service debits for a transfer (with the owner's token).
     *                  Phase 13: then the debit is IDEMPOTENT. Repeating it (a retry after a timeout)
     *                  returns the account without debiting again.
     */
    @Transactional
    public AccountResponse withdraw(UUID id, String ownerId, BigDecimal amount, UUID paymentId) {
        Account account = findOwnedOrThrow(id, ownerId);
        if (paymentId != null) {
            Optional<PaymentOperation> previous = operations.find(paymentId, PaymentLeg.DEBIT);
            if (previous.isPresent()) {
                requireRepeat(previous.get(), PaymentOperationKind.TRANSFER_OUT, id, amount);
                audit.info("action=WITHDRAW_REPLAYED actor={} account={} amount={} payment={}", ownerId, id, amount, paymentId);
                return accountMapper.toResponse(account);
            }
        }
        account.withdraw(amount);
        accountRepository.flush();
        if (paymentId != null) {
            // Same transaction as the balance change. A concurrent duplicate fails on the primary key.
            operations.saveAndFlush(PaymentOperation.moneyMoved(paymentId, PaymentOperationKind.TRANSFER_OUT, id, amount));
        }
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
     *
     * <p>Phase 13 rules, enforced HERE because this is where the money is:
     * <ul>
     *   <li>idempotent: the same payment ID again returns the account without crediting twice</li>
     *   <li>only for a payment whose debit is recorded, for exactly the debited amount: even a
     *       compromised payment-service can't create money</li>
     *   <li>a payment is credited OR refunded, never both (one SETTLEMENT row per payment)</li>
     *   <li>a refund goes back to the account that was debited, nowhere else</li>
     * </ul>
     */
    @Transactional
    public AccountSummary creditForPayment(UUID id, BigDecimal amount, UUID paymentId, boolean refund, String clientId) {
        Account account = findOrThrow(id);
        PaymentOperationKind kind = refund ? PaymentOperationKind.REFUND : PaymentOperationKind.TRANSFER_IN;

        PaymentOperation debit = operations.find(paymentId, PaymentLeg.DEBIT)
                .filter(op -> op.getKind() == PaymentOperationKind.TRANSFER_OUT)
                .orElseThrow(() -> new PaymentOperationConflictException(PaymentOperationConflictException.PAYMENT_NOT_DEBITED,
                        paymentId, "no debit is recorded for this payment"));
        if (debit.getAmount().compareTo(amount) != 0) {
            throw new PaymentOperationConflictException(PaymentOperationConflictException.IDEMPOTENCY_CONFLICT, paymentId,
                    "amount %s differs from the debited %s".formatted(amount, debit.getAmount()));
        }
        if (refund && !debit.getAccountId().equals(id)) {
            throw new PaymentOperationConflictException(PaymentOperationConflictException.IDEMPOTENCY_CONFLICT, paymentId,
                    "a refund must go back to the debited account");
        }
        Optional<PaymentOperation> settled = operations.find(paymentId, PaymentLeg.SETTLEMENT);
        if (settled.isPresent()) {
            requireRepeat(settled.get(), kind, id, amount);
            audit.info("action={}_REPLAYED actor=client:{} account={} amount={} payment={}", kind, clientId, id, amount, paymentId);
            return accountMapper.toSummary(account);
        }

        account.deposit(amount);
        accountRepository.flush();
        operations.saveAndFlush(PaymentOperation.moneyMoved(paymentId, kind, id, amount));
        audit.info("action={} actor=client:{} account={} amount={} payment={}",
                refund ? "REFUND" : "CREDIT", clientId, id, amount, paymentId);
        recordTransaction(account, refund ? TransactionKind.REFUND : TransactionKind.TRANSFER_IN, "CREDIT", amount, paymentId);
        return accountMapper.toSummary(account);
    }

    /**
     * For payment-service's recovery job: "did payment X debit its source?" When the answer is no,
     * a VOID marker is recorded in the same step, so a debit request still on its way (stuck in a
     * queue, retried by a proxy) is refused when it arrives. The answer can never change afterwards.
     *
     * <p>That's how a payment whose debit timed out gets resolved WITHOUT re-sending the debit:
     * a debit needs the customer's token, and the background job doesn't have one.
     */
    @Transactional
    public DebitResolution resolveDebit(UUID paymentId, String clientId) {
        PaymentOperation debit = operations.find(paymentId, PaymentLeg.DEBIT).orElseGet(() -> {
            audit.info("action=VOID_DEBIT actor=client:{} payment={}", clientId, paymentId);
            return operations.saveAndFlush(PaymentOperation.voidedDebit(paymentId));
        });
        return debit.getKind() == PaymentOperationKind.VOID
                ? new DebitResolution(paymentId, DebitResolution.Outcome.VOIDED, null, null)
                : new DebitResolution(paymentId, DebitResolution.Outcome.APPLIED, debit.getAccountId(), debit.getAmount());
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

    /** A repeated payment operation must be EXACTLY the same operation; anything else is refused. */
    private static void requireRepeat(PaymentOperation previous, PaymentOperationKind kind, UUID accountId, BigDecimal amount) {
        UUID paymentId = previous.getPaymentId();
        if (previous.getKind() == PaymentOperationKind.VOID) {
            throw new PaymentOperationConflictException(PaymentOperationConflictException.PAYMENT_VOIDED, paymentId,
                    "the debit was cancelled by payment-service and can no longer be executed");
        }
        if (previous.getKind() != kind) {
            throw new PaymentOperationConflictException(PaymentOperationConflictException.PAYMENT_ALREADY_SETTLED, paymentId,
                    "already settled as %s".formatted(previous.getKind()));
        }
        if (!previous.isSameAs(kind, accountId, amount)) {
            throw new PaymentOperationConflictException(PaymentOperationConflictException.IDEMPOTENCY_CONFLICT, paymentId,
                    "already executed with a different account or amount");
        }
    }

    /**
     * Writes the event into the outbox, in THIS transaction (Phase 13). On rollback it disappears
     * together with the balance change; on commit OutboxRelay delivers it to Kafka.
     */
    private void recordTransaction(Account account, TransactionKind kind, String direction,
                                   BigDecimal amount, UUID paymentId) {
        events.publish(new AccountTransactionEvent(UUID.randomUUID(), AccountTransactionEvent.TYPE,
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
