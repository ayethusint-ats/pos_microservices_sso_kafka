package com.banking.accountservice.entity;

public enum PaymentOperationKind {
    TRANSFER_OUT(PaymentLeg.DEBIT),
    /** "This payment will never be debited": a late-arriving debit request is refused. */
    VOID(PaymentLeg.DEBIT),
    TRANSFER_IN(PaymentLeg.SETTLEMENT),
    REFUND(PaymentLeg.SETTLEMENT);

    private final PaymentLeg leg;

    PaymentOperationKind(PaymentLeg leg) {
        this.leg = leg;
    }

    public PaymentLeg leg() {
        return leg;
    }
}
