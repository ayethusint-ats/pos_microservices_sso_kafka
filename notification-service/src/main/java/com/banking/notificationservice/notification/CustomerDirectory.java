package com.banking.notificationservice.notification;

import com.banking.notificationservice.events.CustomerEvent;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A local copy of "who is who": keycloakUserId → name and e-mail.
 *
 * <p>Filled from the compacted topic banking.customer-events, read from the BEGINNING at every
 * start. No database and no call to user-service: if user-service is down, notifications still
 * get the right address. This is "event-carried state transfer".
 */
@Component
public class CustomerDirectory {

    public record Contact(String firstName, String email) {
    }

    private final Map<String, Contact> contacts = new ConcurrentHashMap<>();

    public void upsert(CustomerEvent customer) {
        contacts.put(customer.keycloakUserId(), new Contact(customer.firstName(), customer.email()));
    }

    /** Tombstone: the profile was deleted, so forget the personal data. */
    public void remove(String keycloakUserId) {
        contacts.remove(keycloakUserId);
    }

    public Optional<Contact> find(String keycloakUserId) {
        return Optional.ofNullable(contacts.get(keycloakUserId));
    }

    public int size() {
        return contacts.size();
    }
}
