package com.banking.notificationservice.notification;

import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The "outbox" of sent e-mails, in memory: the last 50 per customer, so the UI can show them.
 * Simulation only: a real system stores them (and their delivery status) durably.
 */
@Component
public class NotificationStore {

    static final int MAX_PER_USER = 50;

    private final Map<String, Deque<Notification>> byUser = new ConcurrentHashMap<>();
    private final Set<String> seen = ConcurrentHashMap.newKeySet();

    /**
     * @return false if a notification for this (user, event) was already sent: an at-least-once
     *         redelivery must not send the e-mail again.
     */
    public boolean add(Notification notification) {
        if (!seen.add(notification.userId() + ":" + notification.sourceEventId())) {
            return false;
        }
        Deque<Notification> list = byUser.computeIfAbsent(notification.userId(), id -> new ArrayDeque<>());
        synchronized (list) {
            list.addFirst(notification);
            while (list.size() > MAX_PER_USER) {
                list.removeLast();
            }
        }
        return true;
    }

    /** Newest first. */
    public List<Notification> forUser(String userId) {
        Deque<Notification> list = byUser.get(userId);
        if (list == null) {
            return List.of();
        }
        synchronized (list) {
            return List.copyOf(list);
        }
    }
}
