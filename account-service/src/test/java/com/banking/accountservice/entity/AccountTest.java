package com.banking.accountservice.entity;

import com.banking.accountservice.exception.InsufficientFundsException;
import com.banking.accountservice.exception.InvalidAmountException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Pure domain test: no Spring, no mocks. The money rules live in the entity, so we test them here. */
class AccountTest {

    private final Account account = new Account("0000000001", "kc-1", AccountType.CHECKING, "THB");

    @Test
    void newAccountStartsActiveWithZeroBalance() {
        assertThat(account.getStatus()).isEqualTo(AccountStatus.ACTIVE);
        assertThat(account.getBalance()).isEqualByComparingTo("0.00");
    }

    @Test
    void depositAndWithdraw() {
        account.deposit(new BigDecimal("100.50"));
        account.withdraw(new BigDecimal("40.25"));

        assertThat(account.getBalance()).isEqualByComparingTo("60.25");
    }

    @Test
    void withdrawMoreThanBalanceIsRejectedAndBalanceUnchanged() {
        account.deposit(new BigDecimal("10.00"));

        assertThatThrownBy(() -> account.withdraw(new BigDecimal("10.01")))
                .isInstanceOf(InsufficientFundsException.class);
        assertThat(account.getBalance()).isEqualByComparingTo("10.00");
    }

    @Test
    void nonPositiveAmountsAreRejected() {
        assertThatThrownBy(() -> account.deposit(BigDecimal.ZERO)).isInstanceOf(InvalidAmountException.class);
        assertThatThrownBy(() -> account.deposit(new BigDecimal("-5"))).isInstanceOf(InvalidAmountException.class);
        assertThatThrownBy(() -> account.withdraw(null)).isInstanceOf(InvalidAmountException.class);
    }

    @Test
    void moreThanTwoDecimalPlacesIsRejected() {
        assertThatThrownBy(() -> account.deposit(new BigDecimal("1.001")))
                .isInstanceOf(InvalidAmountException.class);
    }
}
