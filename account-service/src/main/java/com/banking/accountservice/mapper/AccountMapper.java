package com.banking.accountservice.mapper;

import com.banking.accountservice.dto.AccountResponse;
import com.banking.accountservice.dto.AccountSummary;
import com.banking.accountservice.dto.BalanceResponse;
import com.banking.accountservice.entity.Account;
import org.springframework.stereotype.Component;

import java.time.Instant;

@Component
public class AccountMapper {

    public AccountResponse toResponse(Account account) {
        return new AccountResponse(
                account.getId(),
                account.getAccountNumber(),
                account.getOwnerId(),
                account.getType(),
                account.getCurrency(),
                account.getBalance(),
                account.getStatus(),
                account.getCreatedAt(),
                account.getUpdatedAt());
    }

    /** Deliberately minimal: no owner, no balance. */
    public AccountSummary toSummary(Account account) {
        return new AccountSummary(account.getId(), account.getAccountNumber(), account.getCurrency(), account.getStatus());
    }

    public BalanceResponse toBalance(Account account) {
        return new BalanceResponse(account.getId(), account.getAccountNumber(),
                account.getBalance(), account.getCurrency(), Instant.now());
    }
}
