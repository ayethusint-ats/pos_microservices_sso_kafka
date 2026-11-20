package com.banking.transactionservice.messaging;

import com.banking.transactionservice.events.AccountTransactionEvent;
import com.banking.transactionservice.service.TransactionHistoryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;

/**
 * Consumes every balance change from account-service.
 *
 * <ul>
 *   <li>Consumer group {@code transaction-service}: each event is processed by ONE instance of this
 *       service, however many run. Another group (notification-service) gets its own copy.</li>
 *   <li>The offset is committed only AFTER this method returns. If the service crashes mid-way, the
 *       event is delivered again, hence the idempotent {@code record()}.</li>
 *   <li>The JSON is converted to the parameter type by the {@code JsonMessageConverter} bean.
 *       If it isn't valid JSON, the error handler sends the raw message to the dead-letter topic.</li>
 * </ul>
 */
@Component
public class AccountTransactionListener {

    private static final Logger log = LoggerFactory.getLogger(AccountTransactionListener.class);

    private final TransactionHistoryService history;

    public AccountTransactionListener(TransactionHistoryService history) {
        this.history = history;
    }

    @KafkaListener(topics = "${banking.kafka.topics.account-transactions}", groupId = "transaction-service")
    public void on(@Payload AccountTransactionEvent event,
                   @Header(KafkaHeaders.RECEIVED_PARTITION) int partition,
                   @Header(KafkaHeaders.OFFSET) long offset) {
        boolean stored = history.record(event);
        log.debug("{} {} {} {} (partition {} offset {})", stored ? "Recorded" : "Skipped",
                event.kind(), event.amount(), event.accountNumber(), partition, offset);
    }
}
