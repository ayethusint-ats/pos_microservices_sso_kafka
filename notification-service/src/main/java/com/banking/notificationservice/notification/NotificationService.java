package com.banking.notificationservice.notification;

import com.banking.notificationservice.events.AccountTransactionEvent;
import com.banking.notificationservice.events.PaymentEvent;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.UUID;

/**
 * Decides who gets which message. Nobody calls this service: it REACTS to what others announce.
 * payment-service doesn't know notifications exist, so adding SMS later means changing only this service.
 */
@Service
public class NotificationService {

    private final CustomerDirectory directory;
    private final NotificationStore store;
    private final EmailSender emailSender;

    public NotificationService(CustomerDirectory directory, NotificationStore store, EmailSender emailSender) {
        this.directory = directory;
        this.store = store;
        this.emailSender = emailSender;
    }

    /** The payer hears about the outcome of THEIR transfer. */
    public void onPayment(PaymentEvent event) {
        String money = money(event.amount(), event.currency());
        String to = event.targetAccountNumber();
        switch (event.eventType()) {
            case "PAYMENT_COMPLETED" -> notify(event.payerId(), event.eventId(),
                    "Transfer sent", "You sent %s to account %s.".formatted(money, to));
            case "PAYMENT_REJECTED", "PAYMENT_FAILED" -> notify(event.payerId(), event.eventId(),
                    "Transfer not completed", "Your transfer of %s to account %s was not completed (%s). No money was moved."
                            .formatted(money, to, event.failureCode()));
            case "PAYMENT_REVERSED" -> notify(event.payerId(), event.eventId(),
                    "Transfer reversed", "Your transfer of %s to account %s could not be delivered (%s). The amount was returned to your account."
                            .formatted(money, to, event.failureCode()));
            case "PAYMENT_NEEDS_RECONCILIATION" -> notify(event.payerId(), event.eventId(),
                    "Transfer being checked", "We are confirming your transfer of %s to account %s. Please don't send it again."
                            .formatted(money, to));
            default -> { /* unknown future event types: ignore (tolerant reader) */ }
        }
    }

    /** The RECIPIENT hears about money arriving. payment-service doesn't even know who that is. */
    public void onAccountTransaction(AccountTransactionEvent event) {
        if ("TRANSFER_IN".equals(event.kind())) {
            notify(event.ownerId(), event.eventId(), "Money received",
                    "You received %s in account %s.".formatted(money(event.amount(), event.currency()), event.accountNumber()));
        }
        // DEPOSIT / WITHDRAWAL / TRANSFER_OUT / REFUND: the customer did it or already gets a payment message.
    }

    private void notify(String userId, UUID sourceEventId, String subject, String body) {
        String email = directory.find(userId).map(CustomerDirectory.Contact::email).orElse(null);
        String greeting = directory.find(userId).map(c -> "Hi " + c.firstName() + ", ").orElse("");
        Notification notification = new Notification(UUID.randomUUID(), userId, email, subject, greeting + body,
                sourceEventId, Instant.now());
        if (store.add(notification)) {
            emailSender.send(notification);
        }
    }

    private static String money(BigDecimal amount, String currency) {
        return (amount == null ? "?" : amount.setScale(2, RoundingMode.UNNECESSARY).toPlainString())
                + (currency == null ? "" : " " + currency);
    }
}
