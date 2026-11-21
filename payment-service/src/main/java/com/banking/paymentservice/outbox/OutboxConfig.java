package com.banking.paymentservice.outbox;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Turns on {@code @Scheduled}: the outbox relay and the payment recovery job. */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
public class OutboxConfig {
}
