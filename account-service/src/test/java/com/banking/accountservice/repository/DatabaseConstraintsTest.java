package com.banking.accountservice.repository;

import com.banking.accountservice.TestcontainersConfiguration;
import com.banking.accountservice.entity.Account;
import com.banking.accountservice.entity.AccountType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Proves the database protects the money even if the Java rules are bypassed,
 * e.g. by a buggy future code path or someone running SQL by hand.
 * This only works against real PostgreSQL, which is one reason we use Testcontainers.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class DatabaseConstraintsTest {

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void negativeBalanceIsRejectedByTheDatabase() {
        Account account = accountRepository.save(new Account("1234567890", "kc-1", AccountType.CHECKING, "THB"));

        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE accounts SET balance = -1 WHERE id = ?", account.getId()))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_accounts_balance_non_negative");
    }

    @Test
    void unknownStatusIsRejectedByTheDatabase() {
        Account account = accountRepository.save(new Account("1234567891", "kc-1", AccountType.CHECKING, "THB"));

        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE accounts SET status = 'DELETED' WHERE id = ?", account.getId()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void flywayAppliedTheMigration() {
        Integer applied = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM flyway_schema_history WHERE version = '1' AND success", Integer.class);
        assertThat(applied).isEqualTo(1);
    }
}
