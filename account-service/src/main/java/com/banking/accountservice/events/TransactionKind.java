package com.banking.accountservice.events;

/** Why a balance changed. Lets consumers word it ("Transfer to …", "Refund") without guessing. */
public enum TransactionKind {
    DEPOSIT,        // customer deposit (cash-in simulation)
    WITHDRAWAL,     // customer withdrawal
    TRANSFER_OUT,   // debit for a payment (customer token + payment ID)
    TRANSFER_IN,    // credit for a payment (payment-service's own token)
    REFUND          // a reversed payment gives the money back to the source account
}
