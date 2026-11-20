package com.banking.notificationservice.messaging;

import com.banking.notificationservice.events.AccountTransactionEvent;
import com.banking.notificationservice.events.PaymentEvent;
import com.banking.notificationservice.notification.NotificationService;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Consumer group "notification-service": it receives its OWN copy of every account event,
 * independent of transaction-service's group. One topic, many independent readers.
 */
@Component
public class NotificationListeners {

    private final NotificationService notifications;

    public NotificationListeners(NotificationService notifications) {
        this.notifications = notifications;
    }

    @KafkaListener(topics = "${banking.kafka.topics.payment-events}", groupId = "notification-service")
    public void onPayment(PaymentEvent event) {
        notifications.onPayment(event);
    }

    @KafkaListener(topics = "${banking.kafka.topics.account-transactions}", groupId = "notification-service")
    public void onAccountTransaction(AccountTransactionEvent event) {
        notifications.onAccountTransaction(event);
    }
}
