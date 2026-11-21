package com.banking.userservice.events;

import com.banking.userservice.entity.Customer;
import com.banking.userservice.repository.CustomerRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;

/**
 * Bootstrap: on startup, publish the CURRENT state of every profile to the compacted topic.
 *
 * <p>Why: profiles created before events existed (Phases 2–10), or whose event was lost (Kafka down
 * right after the commit), would otherwise never reach consumers. On a compacted topic re-publishing
 * is harmless: it simply rewrites the latest value per key. It's a cheap self-healing step, and the
 * reason user-service kept after-commit publishing in Phase 13: money events (account-service,
 * payment-service) can't heal like this, so they use a transactional outbox instead.
 */
@Component
@ConditionalOnProperty(name = "banking.kafka.publish-customer-snapshot-on-startup", havingValue = "true", matchIfMissing = true)
public class CustomerSnapshotPublisher implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(CustomerSnapshotPublisher.class);
    private static final int PAGE_SIZE = 500;

    private final CustomerRepository repository;
    private final CustomerEventPublisher publisher;

    public CustomerSnapshotPublisher(CustomerRepository repository, CustomerEventPublisher publisher) {
        this.repository = repository;
        this.publisher = publisher;
    }

    @Override
    public void run(ApplicationArguments args) {
        long count = 0;
        Page<Customer> page = repository.findAll(PageRequest.of(0, PAGE_SIZE, Sort.by("id")));
        while (true) {
            for (Customer customer : page) {
                publisher.publish(new CustomerChanged(customer.getKeycloakUserId(), CustomerEvent.of(customer)));
                count++;
            }
            if (!page.hasNext()) {
                break;
            }
            page = repository.findAll(page.nextPageable());
        }
        log.info("Published snapshot of {} customer profile(s) to the compacted topic", count);
    }
}
