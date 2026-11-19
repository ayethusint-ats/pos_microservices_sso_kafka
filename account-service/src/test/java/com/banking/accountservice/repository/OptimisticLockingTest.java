package com.banking.accountservice.repository;

import com.banking.accountservice.entity.Account;
import com.banking.accountservice.entity.AccountType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Shows the "lost update" problem and how @Version prevents it.
 *
 * <p>Two requests (A and B) both read the account while its balance is 100. A withdraws 80 and
 * saves. B, still holding the stale copy (balance 100, old version), withdraws 80 too.
 * Without @Version, B's save would overwrite A's, the balance would be 20, and 160 would
 * have left the bank. With @Version, B's UPDATE matches no row and fails.
 */
@SpringBootTest
class OptimisticLockingTest {

    @Autowired
    private AccountRepository accountRepository;

    @Test
    void staleUpdateIsRejected() {
        Account saved = accountRepository.save(new Account("9999999999", "kc-1", AccountType.CHECKING, "THB"));
        saved.deposit(new BigDecimal("100.00"));
        saved = accountRepository.save(saved);

        // Each findById runs in its own transaction, so we get two independent (detached) copies.
        Account requestA = accountRepository.findById(saved.getId()).orElseThrow();
        Account requestB = accountRepository.findById(saved.getId()).orElseThrow();

        requestA.withdraw(new BigDecimal("80.00"));
        accountRepository.save(requestA);                       // succeeds, version is incremented

        requestB.withdraw(new BigDecimal("80.00"));             // in memory B still sees 100
        assertThatThrownBy(() -> accountRepository.save(requestB))
                .isInstanceOf(ObjectOptimisticLockingFailureException.class);

        assertThat(accountRepository.findById(saved.getId()).orElseThrow().getBalance())
                .isEqualByComparingTo("20.00");
    }
}
