package com.banking.notificationservice.notification;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Simulated e-mail gateway: logs instead of sending. In production this would call an SMTP
 * relay or an e-mail API, with its own retries and rate limits.
 */
@Component
public class EmailSender {

    private static final Logger log = LoggerFactory.getLogger("EMAIL");

    public void send(Notification notification) {
        log.info("To: {} | Subject: {} | {}",
                notification.to() == null ? "<unknown address, user " + notification.userId() + ">" : notification.to(),
                notification.subject(), notification.body());
    }
}
