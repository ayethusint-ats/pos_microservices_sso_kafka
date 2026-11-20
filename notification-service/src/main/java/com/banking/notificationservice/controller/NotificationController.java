package com.banking.notificationservice.controller;

import com.banking.notificationservice.notification.Notification;
import com.banking.notificationservice.notification.NotificationStore;
import com.banking.notificationservice.security.Authorization.CurrentUserId;
import com.banking.notificationservice.security.Authorization.CustomerOnly;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** The caller's own (simulated) e-mails, newest first. */
@RestController
@RequestMapping("/api/notifications")
@CustomerOnly
public class NotificationController {

    private final NotificationStore store;

    public NotificationController(NotificationStore store) {
        this.store = store;
    }

    @GetMapping
    public List<Notification> myNotifications(@CurrentUserId String userId) {
        return store.forUser(userId);
    }
}
