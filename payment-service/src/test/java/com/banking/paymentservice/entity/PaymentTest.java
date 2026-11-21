package com.banking.paymentservice.entity;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PaymentTest {

    private Payment newPayment() {
        return new Payment("alice", UUID.randomUUID(), "1234567890", new BigDecimal("10.00"), "rent");
    }

    @Test
    void happyPathGoesPendingDebitedCompleted() {
        Payment payment = newPayment();
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PENDING);

        payment.markDebited();
        payment.complete();

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.COMPLETED);
        assertThat(payment.getStatus().isFinal()).isTrue();
    }

    @Test
    void cannotCompleteWithoutDebit() {
        Payment payment = newPayment();
        assertThatThrownBy(payment::complete).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void cannotReverseBeforeAnythingWasDebited() {
        Payment payment = newPayment();
        assertThatThrownBy(payment::reverse).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void aRefusedCreditIsCompensatedThroughCompensating() {
        Payment payment = newPayment();
        payment.markDebited();
        assertThatThrownBy(payment::reverse).isInstanceOf(IllegalStateException.class);   // refund first

        payment.startCompensation("ACCOUNT_NOT_ACTIVE", "frozen");
        assertThat(payment.getStatus().isFinal()).isFalse();
        payment.reverse();

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.REVERSED);
        assertThat(payment.getFailureCode()).isEqualTo("ACCOUNT_NOT_ACTIVE");          // why it was reversed
        assertThat(payment.getFailureReason()).contains("frozen").contains("returned to the source account");
    }

    @Test
    void reachingAFinalStatusEndsRecovery() {
        Payment payment = newPayment();
        payment.scheduleRecovery(Instant.now());
        payment.recoveryAttempted(Instant.now().plusSeconds(10));
        assertThat(payment.getRecoveryAttempts()).isEqualTo(1);

        payment.fail("DEBIT_NOT_APPLIED", "voided");

        assertThat(payment.getNextRecoveryAt()).isNull();
        assertThatThrownBy(() -> payment.recoveryAttempted(Instant.now())).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void cannotRejectAfterTheMoneyLeftTheSourceAccount() {
        Payment payment = newPayment();
        payment.markDebited();
        assertThatThrownBy(() -> payment.reject("X", "y")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void finalStatesAreFinal() {
        Payment payment = newPayment();
        payment.reject("INSUFFICIENT_FUNDS", "no money");
        assertThatThrownBy(payment::markDebited).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void recipientCannotBeTheSourceAccount() {
        Payment payment = newPayment();
        assertThatThrownBy(() -> payment.accountsResolved(payment.getSourceAccountId(), "THB"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void accountsAreResolvedOnlyBeforeMoneyMoves() {
        Payment payment = newPayment();
        payment.accountsResolved(UUID.randomUUID(), "THB");
        payment.markDebited();
        assertThatThrownBy(() -> payment.accountsResolved(UUID.randomUUID(), "THB"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void amountIsStoredWithTwoDecimals() {
        Payment payment = new Payment("alice", UUID.randomUUID(), "1234567890", new BigDecimal("120"), null);
        assertThat(payment.getAmount()).hasToString("120.00");
    }

    @Test
    void longReasonsAreTruncatedToTheColumnSize() {
        Payment payment = newPayment();
        payment.fail("ACCOUNT_SERVICE_UNAVAILABLE", "x".repeat(2000));
        assertThat(payment.getFailureReason()).hasSize(500);
    }
}
