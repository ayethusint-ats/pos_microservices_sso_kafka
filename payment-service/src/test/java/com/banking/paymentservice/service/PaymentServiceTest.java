package com.banking.paymentservice.service;

import com.banking.paymentservice.client.AccountRejectedException;
import com.banking.paymentservice.client.AccountServiceClient;
import com.banking.paymentservice.client.AccountServiceUnavailableException;
import com.banking.paymentservice.client.AccountView;
import com.banking.paymentservice.dto.PaymentResponse;
import com.banking.paymentservice.dto.TransferRequest;
import com.banking.paymentservice.entity.Payment;
import com.banking.paymentservice.entity.PaymentStatus;
import com.banking.paymentservice.exception.PaymentNotCompletedException;
import com.banking.paymentservice.repository.PaymentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Every way a transfer can end, and in particular: is money ever lost or duplicated?
 */
@ExtendWith(MockitoExtension.class)
class PaymentServiceTest {

    private static final UUID SOURCE = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001");
    private static final UUID TARGET = UUID.fromString("bbbbbbbb-0000-0000-0000-000000000002");
    private static final BigDecimal AMOUNT = new BigDecimal("100.00");

    @Mock
    private PaymentRepository repository;

    @Mock
    private AccountServiceClient accounts;

    private PaymentService service;

    private final List<PaymentStatus> statusesAtSaveTime = new ArrayList<>();

    @BeforeEach
    void setUp() {
        service = new PaymentService(repository, accounts);
        statusesAtSaveTime.clear();
        when(repository.save(any(Payment.class))).thenAnswer(call -> {
            Payment payment = call.getArgument(0);
            statusesAtSaveTime.add(payment.getStatus());    // what the database saw, step by step
            return payment;
        });
    }

    private static TransferRequest request() {
        return new TransferRequest(SOURCE, TARGET, AMOUNT, "rent");
    }

    private void bothAccountsActive(String sourceCurrency, String targetCurrency) {
        when(accounts.getAccount(SOURCE)).thenReturn(new AccountView(SOURCE, "alice", sourceCurrency, BigDecimal.TEN, "ACTIVE"));
        when(accounts.getAccount(TARGET)).thenReturn(new AccountView(TARGET, "alice", targetCurrency, BigDecimal.TEN, "ACTIVE"));
    }

    private static AccountServiceUnavailableException unreachable() {
        return new AccountServiceUnavailableException("unreachable", false, null);
    }

    private static AccountServiceUnavailableException timedOut() {
        return new AccountServiceUnavailableException("no response", true, null);
    }

    private PaymentNotCompletedException transferFails() {
        return catchThrowableOfType(PaymentNotCompletedException.class, () -> service.transfer("alice", request()));
    }

    // ------------------------------------------------------------------ success

    @Test
    void debitsThenCreditsAndRecordsEveryStep() {
        bothAccountsActive("THB", "THB");

        PaymentResponse result = service.transfer("alice", request());

        assertThat(result.status()).isEqualTo(PaymentStatus.COMPLETED);
        assertThat(result.currency()).isEqualTo("THB");
        InOrder order = inOrder(accounts, repository);
        order.verify(repository).save(any());                 // PENDING, before any remote call
        order.verify(accounts).debit(SOURCE, AMOUNT);
        order.verify(repository).save(any());                 // DEBITED checkpoint
        order.verify(accounts).credit(TARGET, AMOUNT);
        order.verify(repository).save(any());                 // COMPLETED
    }

    // ------------------------------------------------------------------ nothing moved

    @Test
    void targetOfAnotherCustomerIsRejectedBeforeAnyMoneyMoves() {
        when(accounts.getAccount(SOURCE)).thenReturn(new AccountView(SOURCE, "alice", "THB", BigDecimal.TEN, "ACTIVE"));
        when(accounts.getAccount(TARGET)).thenThrow(new AccountRejectedException(404, "ACCOUNT_NOT_FOUND", "not found"));

        PaymentNotCompletedException ex = transferFails();

        assertThat(ex.getPayment().status()).isEqualTo(PaymentStatus.REJECTED);
        assertThat(ex.getPayment().failureCode()).isEqualTo("TARGET_ACCOUNT_NOT_FOUND");
        assertThat(ex.isRetryable()).isFalse();
        verify(accounts, never()).debit(any(), any());
    }

    @Test
    void currencyMismatchIsRejectedBeforeAnyMoneyMoves() {
        bothAccountsActive("THB", "USD");

        assertThat(transferFails().getPayment().failureCode()).isEqualTo("CURRENCY_MISMATCH");
        verify(accounts, never()).debit(any(), any());
    }

    @Test
    void frozenTargetIsRejectedBeforeAnyMoneyMoves() {
        when(accounts.getAccount(SOURCE)).thenReturn(new AccountView(SOURCE, "alice", "THB", BigDecimal.TEN, "ACTIVE"));
        when(accounts.getAccount(TARGET)).thenReturn(new AccountView(TARGET, "alice", "THB", BigDecimal.TEN, "FROZEN"));

        assertThat(transferFails().getPayment().failureCode()).isEqualTo("TARGET_ACCOUNT_NOT_ACTIVE");
        verify(accounts, never()).debit(any(), any());
    }

    @Test
    void insufficientFundsIsPassedThroughFromAccountService() {
        bothAccountsActive("THB", "THB");
        when(accounts.debit(SOURCE, AMOUNT)).thenThrow(new AccountRejectedException(422, "INSUFFICIENT_FUNDS", "balance 10.00"));

        PaymentNotCompletedException ex = transferFails();

        assertThat(ex.getPayment().status()).isEqualTo(PaymentStatus.REJECTED);
        assertThat(ex.getPayment().failureCode()).isEqualTo("INSUFFICIENT_FUNDS");
        verify(accounts, never()).credit(any(), any());
    }

    @Test
    void accountServiceDownBeforeTheDebitFailsRetryably() {
        when(accounts.getAccount(SOURCE)).thenThrow(unreachable());

        PaymentNotCompletedException ex = transferFails();

        assertThat(ex.getPayment().status()).isEqualTo(PaymentStatus.FAILED);
        assertThat(ex.isRetryable()).isTrue();
        verify(accounts, never()).debit(any(), any());
    }

    // ------------------------------------------------------------------ the hard cases

    @Test
    void debitTimeoutNeedsReconciliationAndIsNotRetriedBlindly() {
        bothAccountsActive("THB", "THB");
        when(accounts.debit(SOURCE, AMOUNT)).thenThrow(timedOut());

        PaymentResponse result = service.transfer("alice", request());

        assertThat(result.status()).isEqualTo(PaymentStatus.NEEDS_RECONCILIATION);
        assertThat(result.failureCode()).isEqualTo("DEBIT_OUTCOME_UNKNOWN");
        verify(accounts).debit(SOURCE, AMOUNT);              // exactly once: a retry might debit twice
        verify(accounts, never()).credit(any(), any());
    }

    @Test
    void creditRefusedAfterDebitIsCompensatedByRefundingTheSource() {
        bothAccountsActive("THB", "THB");
        when(accounts.credit(TARGET, AMOUNT)).thenThrow(new AccountRejectedException(422, "ACCOUNT_NOT_ACTIVE", "frozen"));

        PaymentNotCompletedException ex = transferFails();

        assertThat(ex.getPayment().status()).isEqualTo(PaymentStatus.REVERSED);
        assertThat(ex.getPayment().failureCode()).isEqualTo("ACCOUNT_NOT_ACTIVE");
        assertThat(ex.isRetryable()).isFalse();
        InOrder order = inOrder(accounts);
        order.verify(accounts).debit(SOURCE, AMOUNT);
        order.verify(accounts).credit(TARGET, AMOUNT);
        order.verify(accounts).credit(SOURCE, AMOUNT);       // the compensating refund
    }

    @Test
    void creditTimeoutIsNotRefundedBecauseTheCreditMayHaveHappened() {
        bothAccountsActive("THB", "THB");
        when(accounts.credit(TARGET, AMOUNT)).thenThrow(timedOut());

        PaymentResponse result = service.transfer("alice", request());

        assertThat(result.status()).isEqualTo(PaymentStatus.NEEDS_RECONCILIATION);
        assertThat(result.failureCode()).isEqualTo("CREDIT_OUTCOME_UNKNOWN");
        verify(accounts, never()).credit(SOURCE, AMOUNT);    // refunding could pay out twice
    }

    @Test
    void failedRefundIsEscalatedNotHidden() {
        bothAccountsActive("THB", "THB");
        when(accounts.credit(TARGET, AMOUNT)).thenThrow(unreachable());
        when(accounts.credit(SOURCE, AMOUNT)).thenThrow(unreachable());

        PaymentResponse result = service.transfer("alice", request());

        assertThat(result.status()).isEqualTo(PaymentStatus.NEEDS_RECONCILIATION);
        assertThat(result.failureCode()).isEqualTo("REFUND_FAILED");
    }

    @Test
    void theDebitedCheckpointIsSavedBeforeTheCredit() {
        bothAccountsActive("THB", "THB");

        service.transfer("alice", request());

        assertThat(statusesAtSaveTime).containsExactly(PaymentStatus.PENDING, PaymentStatus.DEBITED, PaymentStatus.COMPLETED);
    }
}
