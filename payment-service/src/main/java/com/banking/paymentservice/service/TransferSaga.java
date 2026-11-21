package com.banking.paymentservice.service;

import com.banking.paymentservice.client.AccountRejectedException;
import com.banking.paymentservice.client.AccountServiceClient;
import com.banking.paymentservice.client.AccountServiceUnavailableException;
import com.banking.paymentservice.client.AccountView;
import com.banking.paymentservice.client.DebitResolution;
import com.banking.paymentservice.entity.Payment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * A transfer as an orchestrated SAGA: a sequence of local transactions in account-service, each
 * one recorded in the payment before the next starts, with a compensating step (refund) when a
 * later step is refused.
 *
 * <pre>
 *   step            local transaction in account-service     compensation
 *   validate        (reads only)                             -
 *   debit source    TRANSFER_OUT, customer's token           refund (REFUND, service token)
 *   credit target   TRANSFER_IN,  service token              -  (last step: once it succeeded, done)
 * </pre>
 *
 * <p>Two entry points share the same steps:
 * <ul>
 *   <li>{@link #start} runs in the customer's HTTP request, with the customer's token</li>
 *   <li>{@link #resume} runs in PaymentRecoveryJob, WITHOUT a customer token. So it never
 *       re-sends a debit: it RESOLVES the debit instead (account-service answers "applied" or
 *       voids it so it can never happen). Credit and refund use the service token anyway.</li>
 * </ul>
 *
 * <p>Every step is idempotent in account-service (keyed by payment ID), so any step may run
 * twice: once by a request that timed out, again by the recovery job.
 *
 * <p>Rule of thumb in this class: when an outcome is unknown, never guess. Leave the payment in
 * its unfinished status; the recovery job asks again later.
 */
@Component
public class TransferSaga {

    static final String ACCOUNT_SERVICE_UNAVAILABLE = "ACCOUNT_SERVICE_UNAVAILABLE";
    static final String PAYMENT_ALREADY_SETTLED = "PAYMENT_ALREADY_SETTLED";

    private static final Logger log = LoggerFactory.getLogger(TransferSaga.class);

    private final AccountServiceClient accounts;
    private final PaymentStore store;

    public TransferSaga(AccountServiceClient accounts, PaymentStore store) {
        this.accounts = accounts;
        this.store = store;
    }

    /** The live path: a new PENDING payment, inside the customer's request. */
    public Payment start(Payment payment) {
        // ---- Step 1: validate (reads only; nothing can go half-wrong yet) -------------------
        AccountView source;
        AccountView target;
        try {
            // Customer's token: 404 also means "not yours" (account-service checks ownership).
            source = accounts.getOwnAccount(payment.getSourceAccountId());
        } catch (AccountRejectedException ex) {
            return reject(payment, ex.isNotFound() ? "SOURCE_ACCOUNT_NOT_FOUND" : ex.getCode(), ex.getMessage());
        } catch (AccountServiceUnavailableException ex) {
            return fail(payment, ex);
        }
        try {
            // Service token (Phase 10): the recipient may be ANOTHER customer's account.
            target = accounts.findRecipient(payment.getTargetAccountNumber());
        } catch (AccountRejectedException ex) {
            return reject(payment, ex.isNotFound() ? "TARGET_ACCOUNT_NOT_FOUND" : ex.getCode(), ex.getMessage());
        } catch (AccountServiceUnavailableException ex) {
            return fail(payment, ex);
        }
        if (source.id().equals(target.id())) {
            return reject(payment, "SAME_ACCOUNT", "Source and target account are the same");
        }
        if (!source.currency().equals(target.currency())) {
            return reject(payment, "CURRENCY_MISMATCH", "Source account is in %s, target account is in %s"
                    .formatted(source.currency(), target.currency()));
        }
        if (!target.isActive()) {
            // Checked up front because a failing credit would force a refund later. The source's
            // status and balance are NOT pre-checked: the debit enforces them atomically.
            return reject(payment, "TARGET_ACCOUNT_NOT_ACTIVE", "Target account cannot receive payments");
        }
        payment.accountsResolved(target.id(), source.currency());
        payment = store.save(payment);   // checkpoint: the target is known BEFORE any money moves

        // ---- Step 2: debit the source (customer's token = the customer's consent) --------------
        try {
            accounts.debit(payment.getSourceAccountId(), payment.getAmount(), payment.getId());
        } catch (AccountRejectedException ex) {
            return reject(payment, ex.getCode(), ex.getMessage());       // e.g. INSUFFICIENT_FUNDS
        } catch (AccountServiceUnavailableException ex) {
            if (!ex.isOutcomeUnknown()) {
                return fail(payment, ex);                                // certainly nothing happened
            }
            // Timed out even after retries: the money may or may not be gone. Ask, and fence.
            log.warn("Payment {}: debit outcome unknown ({}), resolving it", payment.getId(), ex.getMessage());
            return resolveDebit(payment);
        }
        payment.markDebited();
        payment = store.save(payment);   // the "money is in flight" checkpoint
        return credit(payment);
    }

    /** The recovery path: continue an unfinished payment from wherever it stopped. */
    public Payment resume(Payment payment) {
        return switch (payment.getStatus()) {
            case PENDING -> resolveDebit(payment);
            case DEBITED -> credit(payment);
            case COMPENSATING -> refund(payment);
            default -> payment;    // final: nothing to do
        };
    }

    // ------------------------------------------------------------------ steps

    /**
     * PENDING, debit outcome unknown. account-service says APPLIED (continue with the credit) or
     * VOIDED (nothing moved, and a late debit request will be refused).
     */
    private Payment resolveDebit(Payment payment) {
        DebitResolution resolution;
        try {
            resolution = accounts.resolveDebit(payment.getId());
        } catch (AccountServiceUnavailableException | AccountRejectedException ex) {
            log.warn("Payment {}: debit still unresolved ({}); the recovery job will ask again", payment.getId(), ex.getMessage());
            return note(payment, "DEBIT_OUTCOME_UNKNOWN", "The debit could not be confirmed yet: " + ex.getMessage());
        }
        if (!resolution.applied()) {
            payment.fail("DEBIT_NOT_APPLIED", "The debit could not be confirmed and was cancelled. No money moved");
            return store.save(payment);
        }
        if (payment.getTargetAccountId() == null
                || !payment.getSourceAccountId().equals(resolution.accountId())
                || payment.getAmount().compareTo(resolution.amount()) != 0) {
            // Can't happen with this code; if it does, a person must look before money moves again.
            log.error("Payment {}: account-service reports debit {} {} that doesn't match the payment",
                    payment.getId(), resolution.accountId(), resolution.amount());
            payment.requireReconciliation("DEBIT_MISMATCH", "Recorded debit does not match the payment");
            return store.save(payment);
        }
        payment.markDebited();
        payment = store.save(payment);
        return credit(payment);
    }

    /** DEBITED: credit the target (service token). */
    private Payment credit(Payment payment) {
        try {
            accounts.credit(payment.getTargetAccountId(), payment.getAmount(), payment.getId());
        } catch (AccountServiceUnavailableException ex) {
            // Unknown or not executed: either way, retrying the (idempotent) credit later is safe.
            // Phase 12 refunded at once when the credit "certainly failed"; now the transfer gets a
            // chance to complete instead, which is what the customer asked for.
            log.warn("Payment {}: credit not confirmed ({}); the recovery job will retry it", payment.getId(), ex.getMessage());
            return note(payment, "CREDIT_OUTCOME_UNKNOWN", "The credit could not be confirmed yet: " + ex.getMessage());
        } catch (AccountRejectedException ex) {
            // A definite "no" (e.g. the target was frozen after our check): undo the debit.
            payment.startCompensation(ex.getCode(), ex.getMessage());
            payment = store.save(payment);
            return refund(payment);
        }
        payment.complete();
        return store.save(payment);
    }

    /** COMPENSATING: return the money to the source (service token). */
    private Payment refund(Payment payment) {
        try {
            accounts.refund(payment.getSourceAccountId(), payment.getAmount(), payment.getId());
        } catch (AccountServiceUnavailableException ex) {
            log.warn("Payment {}: refund not confirmed ({}); the recovery job will retry it", payment.getId(), ex.getMessage());
            return payment;   // stays COMPENSATING; the reason (why we're refunding) is kept
        } catch (AccountRejectedException ex) {
            if (PAYMENT_ALREADY_SETTLED.equals(ex.getCode())) {
                // An earlier credit attempt landed after all (account-service allows credit OR refund,
                // never both). The money reached the target: the transfer is complete.
                log.warn("Payment {}: refund refused because the credit had landed; completing", payment.getId());
                payment.complete();
                return store.save(payment);
            }
            log.error("Payment {}: refund refused ({}): debited, not credited, not refunded", payment.getId(), ex.getMessage());
            payment.requireReconciliation("REFUND_FAILED",
                    "%s, and the refund was refused (%s)".formatted(payment.getFailureReason(), ex.getMessage()));
            return store.save(payment);
        }
        payment.reverse();
        return store.save(payment);
    }

    // ------------------------------------------------------------------ helpers

    private Payment reject(Payment payment, String code, String reason) {
        payment.reject(code, reason);
        return store.save(payment);
    }

    private Payment fail(Payment payment, AccountServiceUnavailableException ex) {
        log.warn("Payment {} failed, nothing was moved: {}", payment.getId(), ex.getMessage());
        payment.fail(ACCOUNT_SERVICE_UNAVAILABLE, ex.getMessage());
        return store.save(payment);
    }

    private Payment note(Payment payment, String code, String reason) {
        if (code.equals(payment.getFailureCode())) {
            return payment;     // same issue as last time: no write needed
        }
        payment.noteIssue(code, reason);
        return store.save(payment);
    }
}
