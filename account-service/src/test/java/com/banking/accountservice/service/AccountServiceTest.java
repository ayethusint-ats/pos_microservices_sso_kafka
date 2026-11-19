package com.banking.accountservice.service;

import com.banking.accountservice.dto.AccountResponse;
import com.banking.accountservice.entity.Account;
import com.banking.accountservice.entity.AccountType;
import com.banking.accountservice.exception.ResourceNotFoundException;
import com.banking.accountservice.exception.UnsupportedCurrencyException;
import com.banking.accountservice.mapper.AccountMapper;
import com.banking.accountservice.repository.AccountRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AccountServiceTest {

    @Mock
    private AccountRepository accountRepository;

    @Mock
    private AccountNumberGenerator accountNumberGenerator;

    private AccountService accountService;

    @BeforeEach
    void setUp() {
        accountService = new AccountService(accountRepository, accountNumberGenerator, new AccountMapper());
    }

    @Test
    void open_retriesWhenGeneratedAccountNumberAlreadyExists() {
        when(accountNumberGenerator.next()).thenReturn("1111111111", "2222222222");
        when(accountRepository.existsByAccountNumber("1111111111")).thenReturn(true);
        when(accountRepository.existsByAccountNumber("2222222222")).thenReturn(false);
        when(accountRepository.saveAndFlush(any(Account.class))).thenAnswer(inv -> inv.getArgument(0));

        AccountResponse response = accountService.open("kc-1", AccountType.SAVINGS, "THB", "kc-1");

        assertThat(response.accountNumber()).isEqualTo("2222222222");
        assertThat(response.balance()).isEqualByComparingTo("0");
    }

    @Test
    void open_rejectsUnknownCurrency() {
        assertThatThrownBy(() -> accountService.open("kc-1", AccountType.SAVINGS, "XYZ", "kc-1"))
                .isInstanceOf(UnsupportedCurrencyException.class);
        verify(accountRepository, never()).saveAndFlush(any());
    }

    @Test
    void deposit_onSomeoneElsesAccountLooksLikeNotFound() {
        UUID id = UUID.randomUUID();
        // The repository is asked for (id AND owner): another user's account simply isn't found.
        when(accountRepository.findByIdAndOwnerId(id, "intruder")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> accountService.deposit(id, "intruder", BigDecimal.TEN))
                .isInstanceOf(ResourceNotFoundException.class);
        verify(accountRepository, never()).flush();
    }
}
