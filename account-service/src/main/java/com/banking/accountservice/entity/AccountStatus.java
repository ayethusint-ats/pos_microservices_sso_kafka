package com.banking.accountservice.entity;

/**
 * Only ACTIVE accounts can move money. Phase 8 adds ADMIN operations to freeze or close accounts.
 */
public enum AccountStatus {
    ACTIVE,
    FROZEN,
    CLOSED
}
