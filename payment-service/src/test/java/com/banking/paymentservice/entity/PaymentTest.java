package com.banking.paymentservice.entity;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PaymentTest {

    private Payment newPayment() {
        return new Payment("alice", UUID.randomUUID(), UUID.randomUUID(), new BigDecimal("10.00"), "rent");
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
        assertThatThrownBy(() -> payment.reverse("X", "y")).isInstanceOf(IllegalStateException.class);
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
    void amountIsStoredWithTwoDecimals() {
        Payment payment = new Payment("alice", UUID.randomUUID(), UUID.randomUUID(), new BigDecimal("120"), null);
        assertThat(payment.getAmount()).hasToString("120.00");
    }

    @Test
    void longReasonsAreTruncatedToTheColumnSize() {
        Payment payment = newPayment();
        payment.fail("ACCOUNT_SERVICE_UNAVAILABLE", "x".repeat(2000));
        assertThat(payment.getFailureReason()).hasSize(500);
    }
}
