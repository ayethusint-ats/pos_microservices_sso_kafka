package com.banking.accountservice.outbox;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Turns on {@code @Scheduled}, used by the outbox relay. */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
public class OutboxConfig {
}
