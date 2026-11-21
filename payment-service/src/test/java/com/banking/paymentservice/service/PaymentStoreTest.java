package com.banking.paymentservice.service;

import com.banking.paymentservice.entity.Payment;
import com.banking.paymentservice.events.PaymentEvent;
import com.banking.paymentservice.events.PaymentEventPublisher;
import com.banking.paymentservice.repository.PaymentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Only FINAL statuses are announced, so each payment produces exactly one event. */
@ExtendWith(MockitoExtension.class)
class PaymentStoreTest {

    @Mock
    private PaymentRepository repository;

    @Mock
    private PaymentEventPublisher events;

    private PaymentStore store;
    private Payment payment;

    @BeforeEach
    void setUp() {
        store = new PaymentStore(repository, events);
        when(repository.saveAndFlush(any(Payment.class))).thenAnswer(call -> call.getArgument(0));
        payment = new Payment("alice", UUID.randomUUID(), "2222222222", new BigDecimal("5.00"), "rent");
    }

    @Test
    void unfinishedStepsAreSavedWithoutAnEvent() {
        store.save(payment);                               // PENDING
        payment.accountsResolved(UUID.randomUUID(), "THB");
        payment.markDebited();
        store.save(payment);                               // DEBITED

        verify(events, never()).publish(any());
    }

    @Test
    void theFinalStatusIsAnnouncedInTheSameTransaction() {
        payment.reject("INSUFFICIENT_FUNDS", "no money");

        store.save(payment);

        ArgumentCaptor<PaymentEvent> event = ArgumentCaptor.forClass(PaymentEvent.class);
        verify(events).publish(event.capture());
        assertThat(event.getValue().eventType()).isEqualTo("PAYMENT_REJECTED");
        assertThat(event.getValue().failureCode()).isEqualTo("INSUFFICIENT_FUNDS");
    }
}
