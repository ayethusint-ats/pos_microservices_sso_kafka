package com.banking.accountservice.outbox;

import com.banking.accountservice.TestcontainersConfiguration;
import com.banking.accountservice.dto.AccountResponse;
import com.banking.accountservice.entity.AccountType;
import com.banking.accountservice.exception.InsufficientFundsException;
import com.banking.accountservice.service.AccountService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.IllegalTransactionStateException;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.banking.accountservice.support.TestUsers.ALICE_ID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

/** The balance change and its event are committed together; the relay then delivers and marks it. */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class OutboxIntegrationTest {

    @Autowired
    private AccountService accountService;

    @Autowired
    private OutboxRepository outbox;

    @Autowired
    private OutboxWriter writer;

    @Test
    void aCommittedChangeLeavesAnEventThatTheRelayPublishes() {
        AccountResponse account = accountService.open(ALICE_ID, AccountType.SAVINGS, "THB", ALICE_ID);
        accountService.deposit(account.id(), ALICE_ID, new BigDecimal("12.34"));

        List<OutboxEvent> rows = rowsFor(account.id());
        assertThat(rows).hasSize(1);
        assertThat(rows.getFirst().getTopic()).isEqualTo("banking.account-transactions");
        assertThat(rows.getFirst().getPayload()).contains("\"kind\":\"DEPOSIT\"").contains(rows.getFirst().getId().toString());

        await().untilAsserted(() -> assertThat(rowsFor(account.id()).getFirst().getPublishedAt()).isNotNull());
        assertThat(rowsFor(account.id()).getFirst().getAttempts()).isEqualTo(1);
    }

    @Test
    void aRolledBackChangeLeavesNoEvent() {
        AccountResponse account = accountService.open(ALICE_ID, AccountType.SAVINGS, "THB", ALICE_ID);

        assertThatThrownBy(() -> accountService.withdraw(account.id(), ALICE_ID, BigDecimal.ONE))
                .isInstanceOf(InsufficientFundsException.class);

        assertThat(rowsFor(account.id())).isEmpty();
    }

    @Test
    void writingToTheOutboxWithoutABusinessTransactionIsABug() {
        // MANDATORY propagation: an event committed on its own would bring the dual-write gap back.
        assertThatThrownBy(() -> writer.append("t", "k", UUID.randomUUID(), Map.of("x", 1)))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    private List<OutboxEvent> rowsFor(UUID accountId) {
        return outbox.findAll().stream().filter(e -> e.getMessageKey().equals(accountId.toString())).toList();
    }
}
