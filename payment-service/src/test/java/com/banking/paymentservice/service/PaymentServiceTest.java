package com.banking.paymentservice.service;

import com.banking.paymentservice.client.AccountRejectedException;
import com.banking.paymentservice.client.AccountServiceClient;
import com.banking.paymentservice.client.AccountServiceUnavailableException;
import com.banking.paymentservice.client.AccountView;
import com.banking.paymentservice.client.DebitResolution;
import com.banking.paymentservice.dto.PaymentResponse;
import com.banking.paymentservice.dto.TransferRequest;
import com.banking.paymentservice.entity.Payment;
import com.banking.paymentservice.entity.PaymentStatus;
import com.banking.paymentservice.exception.IdempotencyKeyReusedException;
import com.banking.paymentservice.exception.PaymentNotCompletedException;
import com.banking.paymentservice.repository.PaymentRepository;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Every way a transfer can end, and in particular: is money ever lost or duplicated?
 * PaymentService + the real TransferSaga; account-service and the database are mocked.
 */
@ExtendWith(MockitoExtension.class)
class PaymentServiceTest {

    private static final UUID SOURCE = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001");
    private static final UUID TARGET = UUID.fromString("bbbbbbbb-0000-0000-0000-000000000002");
    private static final String TARGET_NUMBER = "2222222222";
    private static final BigDecimal AMOUNT = new BigDecimal("100.00");

    @Mock
    private PaymentRepository repository;

    @Mock
    private PaymentStore store;

    @Mock
    private AccountServiceClient accounts;

    private PaymentService service;
    private TransferSaga saga;

    private final List<PaymentStatus> statusesAtSaveTime = new ArrayList<>();

    @BeforeEach
    void setUp() {
        saga = new TransferSaga(accounts, store);
        service = new PaymentService(repository, store, saga,
                new RecoveryProperties(Duration.ofSeconds(30), Duration.ofSeconds(10), Duration.ofMinutes(5), 10, 20),
                ObservationRegistry.NOOP);
        statusesAtSaveTime.clear();
        // lenient: the Idempotency-Key replays never save anything, and that's the point.
        lenient().when(store.save(any(Payment.class))).thenAnswer(call -> {
            Payment payment = call.getArgument(0);
            statusesAtSaveTime.add(payment.getStatus());    // what the database saw, step by step
            return payment;
        });
    }

    private static TransferRequest request() {
        return new TransferRequest(SOURCE, TARGET_NUMBER, AMOUNT, "rent");
    }

    private void source(String currency) {
        when(accounts.getOwnAccount(SOURCE)).thenReturn(new AccountView(SOURCE, "1111111111", "alice", currency, BigDecimal.TEN, "ACTIVE"));
    }

    private void recipient(String currency, String status) {
        when(accounts.findRecipient(TARGET_NUMBER)).thenReturn(new AccountView(TARGET, TARGET_NUMBER, null, currency, null, status));
    }

    private void bothAccountsActive() {
        source("THB");
        recipient("THB", "ACTIVE");
    }

    private static AccountServiceUnavailableException unreachable() {
        return new AccountServiceUnavailableException("unreachable", false, null);
    }

    private static AccountServiceUnavailableException timedOut() {
        return new AccountServiceUnavailableException("no response", true, null);
    }

    private static DebitResolution applied() {
        return new DebitResolution(null, DebitResolution.Outcome.APPLIED, SOURCE, AMOUNT);
    }

    private PaymentResponse transfer() {
        return service.transfer("alice", null, request()).payment();
    }

    private PaymentNotCompletedException transferFails() {
        return catchThrowableOfType(PaymentNotCompletedException.class, () -> service.transfer("alice", null, request()));
    }

    // ------------------------------------------------------------------ success

    @Test
    void debitsWithTheCustomersTokenThenCreditsWithTheServiceToken() {
        bothAccountsActive();

        PaymentResponse result = transfer();

        assertThat(result.status()).isEqualTo(PaymentStatus.COMPLETED);
        assertThat(result.currency()).isEqualTo("THB");
        assertThat(result.targetAccountId()).isEqualTo(TARGET);
        InOrder order = inOrder(accounts);
        order.verify(accounts).getOwnAccount(SOURCE);          // customer token
        order.verify(accounts).findRecipient(TARGET_NUMBER);   // service token
        order.verify(accounts).debit(eq(SOURCE), eq(AMOUNT), any());    // customer token: consent
        order.verify(accounts).credit(eq(TARGET), eq(AMOUNT), any());   // service token
        // created, target resolved (checkpoint before money moves), debited, completed
        assertThat(statusesAtSaveTime).containsExactly(
                PaymentStatus.PENDING, PaymentStatus.PENDING, PaymentStatus.DEBITED, PaymentStatus.COMPLETED);
    }

    // ------------------------------------------------------------------ nothing moved

    @Test
    void sourceOfAnotherCustomerIsRejected() {
        when(accounts.getOwnAccount(SOURCE)).thenThrow(new AccountRejectedException(404, "ACCOUNT_NOT_FOUND", "not found"));

        assertThat(transferFails().getPayment().failureCode()).isEqualTo("SOURCE_ACCOUNT_NOT_FOUND");
        verify(accounts, never()).findRecipient(any());
        verify(accounts, never()).debit(any(), any(), any());
    }

    @Test
    void unknownAccountNumberIsRejectedBeforeAnyMoneyMoves() {
        source("THB");
        when(accounts.findRecipient(TARGET_NUMBER)).thenThrow(new AccountRejectedException(404, "ACCOUNT_NOT_FOUND", "not found"));

        PaymentNotCompletedException ex = transferFails();

        assertThat(ex.getPayment().status()).isEqualTo(PaymentStatus.REJECTED);
        assertThat(ex.getPayment().failureCode()).isEqualTo("TARGET_ACCOUNT_NOT_FOUND");
        assertThat(ex.isRetryable()).isFalse();
        verify(accounts, never()).debit(any(), any(), any());
    }

    @Test
    void ownSourceNumberAsRecipientIsRejected() {
        source("THB");
        when(accounts.findRecipient(TARGET_NUMBER)).thenReturn(new AccountView(SOURCE, TARGET_NUMBER, null, "THB", null, "ACTIVE"));

        assertThat(transferFails().getPayment().failureCode()).isEqualTo("SAME_ACCOUNT");
        verify(accounts, never()).debit(any(), any(), any());
    }

    @Test
    void currencyMismatchIsRejectedBeforeAnyMoneyMoves() {
        source("THB");
        recipient("USD", "ACTIVE");

        assertThat(transferFails().getPayment().failureCode()).isEqualTo("CURRENCY_MISMATCH");
        verify(accounts, never()).debit(any(), any(), any());
    }

    @Test
    void frozenTargetIsRejectedBeforeAnyMoneyMoves() {
        source("THB");
        recipient("THB", "FROZEN");

        assertThat(transferFails().getPayment().failureCode()).isEqualTo("TARGET_ACCOUNT_NOT_ACTIVE");
        verify(accounts, never()).debit(any(), any(), any());
    }

    @Test
    void insufficientFundsIsPassedThroughFromAccountService() {
        bothAccountsActive();
        when(accounts.debit(eq(SOURCE), eq(AMOUNT), any())).thenThrow(new AccountRejectedException(422, "INSUFFICIENT_FUNDS", "balance 10.00"));

        PaymentNotCompletedException ex = transferFails();

        assertThat(ex.getPayment().status()).isEqualTo(PaymentStatus.REJECTED);
        assertThat(ex.getPayment().failureCode()).isEqualTo("INSUFFICIENT_FUNDS");
        verify(accounts, never()).credit(any(), any(), any());
        verify(accounts, never()).resolveDebit(any());      // a clean "no": nothing to fence
    }

    @Test
    void accountServiceDownBeforeTheDebitFailsRetryably() {
        when(accounts.getOwnAccount(SOURCE)).thenThrow(unreachable());

        PaymentNotCompletedException ex = transferFails();

        assertThat(ex.getPayment().status()).isEqualTo(PaymentStatus.FAILED);
        assertThat(ex.isRetryable()).isTrue();
        verify(accounts, never()).debit(any(), any(), any());
    }

    @Test
    void debitCertainlyNotExecutedFailsWithoutFencing() {
        bothAccountsActive();
        when(accounts.debit(eq(SOURCE), eq(AMOUNT), any())).thenThrow(unreachable());

        assertThat(transferFails().getPayment().status()).isEqualTo(PaymentStatus.FAILED);
        verify(accounts, never()).resolveDebit(any());
    }

    // ------------------------------------------------------------------ debit outcome unknown: the fence

    @Test
    void unknownDebitThatHadHappenedContinuesWithTheCredit() {
        bothAccountsActive();
        when(accounts.debit(eq(SOURCE), eq(AMOUNT), any())).thenThrow(timedOut());
        when(accounts.resolveDebit(any())).thenReturn(applied());

        assertThat(transfer().status()).isEqualTo(PaymentStatus.COMPLETED);
        verify(accounts).debit(eq(SOURCE), eq(AMOUNT), any());           // never re-sent by the saga
        verify(accounts).credit(eq(TARGET), eq(AMOUNT), any());
    }

    @Test
    void unknownDebitThatHadNotHappenedIsVoidedAndFailsSafely() {
        bothAccountsActive();
        when(accounts.debit(eq(SOURCE), eq(AMOUNT), any())).thenThrow(timedOut());
        when(accounts.resolveDebit(any())).thenReturn(new DebitResolution(null, DebitResolution.Outcome.VOIDED, null, null));

        PaymentNotCompletedException ex = transferFails();

        assertThat(ex.getPayment().status()).isEqualTo(PaymentStatus.FAILED);
        assertThat(ex.getPayment().failureCode()).isEqualTo("DEBIT_NOT_APPLIED");
        assertThat(ex.isRetryable()).isTrue();                            // nothing moved: 503, try again
        verify(accounts, never()).credit(any(), any(), any());
    }

    @Test
    void unknownDebitThatCannotBeResolvedYetIsAcceptedAndLeftToRecovery() {
        bothAccountsActive();
        when(accounts.debit(eq(SOURCE), eq(AMOUNT), any())).thenThrow(timedOut());
        when(accounts.resolveDebit(any())).thenThrow(timedOut());

        PaymentResponse result = transfer();

        assertThat(result.status()).isEqualTo(PaymentStatus.PENDING);     // 202: never guess
        assertThat(result.failureCode()).isEqualTo("DEBIT_OUTCOME_UNKNOWN");
        verify(accounts, never()).credit(any(), any(), any());
    }

    // ------------------------------------------------------------------ after the debit

    @Test
    void creditRefusedAfterDebitIsCompensatedByRefundingTheSource() {
        bothAccountsActive();
        when(accounts.credit(eq(TARGET), eq(AMOUNT), any())).thenThrow(new AccountRejectedException(422, "ACCOUNT_NOT_ACTIVE", "frozen"));

        PaymentNotCompletedException ex = transferFails();

        assertThat(ex.getPayment().status()).isEqualTo(PaymentStatus.REVERSED);
        assertThat(ex.getPayment().failureCode()).isEqualTo("ACCOUNT_NOT_ACTIVE");
        assertThat(ex.isRetryable()).isFalse();
        assertThat(statusesAtSaveTime).containsSubsequence(PaymentStatus.DEBITED, PaymentStatus.COMPENSATING, PaymentStatus.REVERSED);
        InOrder order = inOrder(accounts);
        order.verify(accounts).debit(eq(SOURCE), eq(AMOUNT), any());
        order.verify(accounts).credit(eq(TARGET), eq(AMOUNT), any());
        order.verify(accounts).refund(eq(SOURCE), eq(AMOUNT), any());       // the compensating step
    }

    @Test
    void creditWithoutAnswerIsNeitherRefundedNorGivenUp() {
        bothAccountsActive();
        when(accounts.credit(eq(TARGET), eq(AMOUNT), any())).thenThrow(timedOut());

        PaymentResponse result = transfer();

        assertThat(result.status()).isEqualTo(PaymentStatus.DEBITED);       // the recovery job retries the credit
        assertThat(result.failureCode()).isEqualTo("CREDIT_OUTCOME_UNKNOWN");
        verify(accounts, never()).refund(any(), any(), any());              // refunding could pay out twice
    }

    @Test
    void refundWithoutAnswerStaysCompensating() {
        bothAccountsActive();
        when(accounts.credit(eq(TARGET), eq(AMOUNT), any())).thenThrow(new AccountRejectedException(422, "ACCOUNT_NOT_ACTIVE", "frozen"));
        when(accounts.refund(eq(SOURCE), eq(AMOUNT), any())).thenThrow(unreachable());

        assertThat(transfer().status()).isEqualTo(PaymentStatus.COMPENSATING);
    }

    @Test
    void refundRefusedBecauseALateCreditLandedMeansTheTransferCompleted() {
        bothAccountsActive();
        when(accounts.credit(eq(TARGET), eq(AMOUNT), any())).thenThrow(new AccountRejectedException(422, "ACCOUNT_NOT_ACTIVE", "frozen"));
        when(accounts.refund(eq(SOURCE), eq(AMOUNT), any()))
                .thenThrow(new AccountRejectedException(422, "PAYMENT_ALREADY_SETTLED", "already settled as TRANSFER_IN"));

        assertThat(transfer().status()).isEqualTo(PaymentStatus.COMPLETED);
    }

    @Test
    void refundRefusedForAnyOtherReasonIsEscalatedNotHidden() {
        bothAccountsActive();
        when(accounts.credit(eq(TARGET), eq(AMOUNT), any())).thenThrow(new AccountRejectedException(422, "ACCOUNT_NOT_ACTIVE", "frozen"));
        when(accounts.refund(eq(SOURCE), eq(AMOUNT), any())).thenThrow(new AccountRejectedException(422, "ACCOUNT_NOT_ACTIVE", "source closed"));

        PaymentResponse result = transfer();

        assertThat(result.status()).isEqualTo(PaymentStatus.NEEDS_RECONCILIATION);
        assertThat(result.failureCode()).isEqualTo("REFUND_FAILED");
    }

    // ------------------------------------------------------------------ recovery (no customer token)

    @Test
    void resumingAPendingPaymentResolvesTheDebitInsteadOfResendingIt() {
        Payment pending = new Payment("alice", SOURCE, TARGET_NUMBER, AMOUNT, "rent");
        pending.accountsResolved(TARGET, "THB");
        when(accounts.resolveDebit(any())).thenReturn(applied());

        assertThat(saga.resume(pending).getStatus()).isEqualTo(PaymentStatus.COMPLETED);
        verify(accounts, never()).debit(any(), any(), any());               // would need the customer's token
        verify(accounts, never()).getOwnAccount(any());
    }

    @Test
    void resumingACompensatingPaymentRetriesTheRefund() {
        Payment compensating = new Payment("alice", SOURCE, TARGET_NUMBER, AMOUNT, "rent");
        compensating.accountsResolved(TARGET, "THB");
        compensating.markDebited();
        compensating.startCompensation("ACCOUNT_NOT_ACTIVE", "frozen");

        assertThat(saga.resume(compensating).getStatus()).isEqualTo(PaymentStatus.REVERSED);
        verify(accounts).refund(eq(SOURCE), eq(AMOUNT), any());
        verify(accounts, never()).credit(any(), any(), any());
    }

    // ------------------------------------------------------------------ Idempotency-Key

    @Test
    void sameKeySameRequestReturnsTheFirstOutcomeWithoutExecutingAgain() {
        Payment earlier = completedPayment("key-12345678", request());
        when(repository.findByPayerIdAndIdempotencyKey("alice", "key-12345678")).thenReturn(Optional.of(earlier));

        TransferResult result = service.transfer("alice", "key-12345678", request());

        assertThat(result.replayed()).isTrue();
        assertThat(result.payment().status()).isEqualTo(PaymentStatus.COMPLETED);
        verifyNoInteractions(accounts);
        verify(store, never()).save(any());
    }

    @Test
    void sameKeyDifferentRequestIsRefused() {
        Payment earlier = completedPayment("key-12345678", request());
        when(repository.findByPayerIdAndIdempotencyKey("alice", "key-12345678")).thenReturn(Optional.of(earlier));
        TransferRequest otherAmount = new TransferRequest(SOURCE, TARGET_NUMBER, new BigDecimal("999.00"), "rent");

        assertThatThrownBy(() -> service.transfer("alice", "key-12345678", otherAmount))
                .isInstanceOf(IdempotencyKeyReusedException.class);
        verifyNoInteractions(accounts);
    }

    @Test
    void replayOfARejectedPaymentFailsTheSameWayAgain() {
        Payment earlier = new Payment("alice", SOURCE, TARGET_NUMBER, AMOUNT, "rent", "key-12345678",
                PaymentService.fingerprint(request()));
        earlier.reject("INSUFFICIENT_FUNDS", "no money");
        when(repository.findByPayerIdAndIdempotencyKey("alice", "key-12345678")).thenReturn(Optional.of(earlier));

        PaymentNotCompletedException ex = catchThrowableOfType(PaymentNotCompletedException.class,
                () -> service.transfer("alice", "key-12345678", request()));

        assertThat(ex.isReplayed()).isTrue();
        assertThat(ex.getPayment().failureCode()).isEqualTo("INSUFFICIENT_FUNDS");
        verifyNoInteractions(accounts);
    }

    @Test
    void twoRequestsWithTheSameKeyAtTheSameMomentCreateOnePayment() {
        Payment winner = completedPayment("key-12345678", request());
        when(repository.findByPayerIdAndIdempotencyKey("alice", "key-12345678"))
                .thenReturn(Optional.empty())              // not there yet when this request looked...
                .thenReturn(Optional.of(winner));          // ...but the other request inserted it first
        when(store.save(any(Payment.class))).thenThrow(new DataIntegrityViolationException("uk_payments_payer_idempotency_key"));

        TransferResult result = service.transfer("alice", "key-12345678", request());

        assertThat(result.replayed()).isTrue();
        verifyNoInteractions(accounts);
    }

    @Test
    void fingerprintIgnoresHowTheAmountIsWrittenButNotWhatItIs() {
        String base = PaymentService.fingerprint(request());
        assertThat(PaymentService.fingerprint(new TransferRequest(SOURCE, TARGET_NUMBER, new BigDecimal("100"), "rent"))).isEqualTo(base);
        assertThat(PaymentService.fingerprint(new TransferRequest(SOURCE, TARGET_NUMBER, new BigDecimal("100.01"), "rent"))).isNotEqualTo(base);
        assertThat(PaymentService.fingerprint(new TransferRequest(SOURCE, TARGET_NUMBER, AMOUNT, "other"))).isNotEqualTo(base);
    }

    private static Payment completedPayment(String key, TransferRequest request) {
        Payment payment = new Payment("alice", request.sourceAccountId(), request.targetAccountNumber(), request.amount(),
                request.description(), key, PaymentService.fingerprint(request));
        payment.accountsResolved(TARGET, "THB");
        payment.markDebited();
        payment.complete();
        return payment;
    }
}
