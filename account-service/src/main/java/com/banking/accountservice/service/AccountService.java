package com.banking.accountservice.service;

import com.banking.accountservice.dto.AccountResponse;
import com.banking.accountservice.dto.BalanceResponse;
import com.banking.accountservice.dto.CreateAccountRequest;
import com.banking.accountservice.entity.Account;
import com.banking.accountservice.exception.ResourceNotFoundException;
import com.banking.accountservice.exception.UnsupportedCurrencyException;
import com.banking.accountservice.mapper.AccountMapper;
import com.banking.accountservice.repository.AccountRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Currency;
import java.util.List;
import java.util.UUID;

@Service
@Transactional(readOnly = true)
public class AccountService {

    private static final String RESOURCE = "Account";
    private static final int MAX_ACCOUNT_NUMBER_ATTEMPTS = 5;

    private final AccountRepository accountRepository;
    private final AccountNumberGenerator accountNumberGenerator;
    private final AccountMapper accountMapper;

    public AccountService(AccountRepository accountRepository,
                          AccountNumberGenerator accountNumberGenerator,
                          AccountMapper accountMapper) {
        this.accountRepository = accountRepository;
        this.accountNumberGenerator = accountNumberGenerator;
        this.accountMapper = accountMapper;
    }

    @Transactional
    public AccountResponse open(CreateAccountRequest request) {
        String currency = requireSupportedCurrency(request.currency());
        Account account = new Account(uniqueAccountNumber(), request.ownerId(), request.type(), currency);
        return accountMapper.toResponse(accountRepository.saveAndFlush(account));
    }

    public AccountResponse getById(UUID id) {
        return accountMapper.toResponse(findOrThrow(id));
    }

    public List<AccountResponse> getByOwner(String ownerId) {
        return accountRepository.findByOwnerIdOrderByCreatedAtAsc(ownerId).stream()
                .map(accountMapper::toResponse)
                .toList();
    }

    public BalanceResponse getBalance(UUID id) {
        return accountMapper.toBalance(findOrThrow(id));
    }

    /**
     * Load, change through the entity's own method, and flush, all in one local DB transaction.
     * If anything throws, the transaction rolls back and the balance is unchanged.
     *
     * <p>Known gap, fixed in Phase 13: a client that retries a timed-out deposit would
     * deposit twice. That's why payment requests get an Idempotency-Key.
     */
    @Transactional
    public AccountResponse deposit(UUID id, BigDecimal amount) {
        Account account = findOrThrow(id);
        account.deposit(amount);
        accountRepository.flush();   // surfaces optimistic-lock conflicts here, mapped to 409
        return accountMapper.toResponse(account);
    }

    @Transactional
    public AccountResponse withdraw(UUID id, BigDecimal amount) {
        Account account = findOrThrow(id);
        account.withdraw(amount);
        accountRepository.flush();
        return accountMapper.toResponse(account);
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
