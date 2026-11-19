package com.banking.paymentservice.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.oauth2.core.OAuth2AuthorizationException;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.UnknownHostException;
import java.net.http.HttpConnectTimeoutException;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * What payment-service may ask of account-service, with HTTP turned into domain-level outcomes.
 *
 * <p>Callers never see HTTP status codes or Spring exceptions. They get one of three things:
 * <ol>
 *   <li>a result: the operation happened</li>
 *   <li>{@link AccountRejectedException}: account-service said no, and nothing changed</li>
 *   <li>{@link AccountServiceUnavailableException}: no usable answer, with
 *       {@code outcomeUnknown} telling whether the operation might still have happened</li>
 * </ol>
 *
 * <p>Two identities (Phase 10): reading the source and debiting it use the CUSTOMER's token
 * (proof of consent, ownership enforced by account-service); finding the recipient and crediting
 * use payment-service's OWN token (a permission no customer has).
 *
 * <p>The classification rule (Phase 9):
 * <pre>
 *   connection never opened (refused, connect timeout, unknown host) → did NOT happen
 *   4xx response                                                     → did NOT happen (refused)
 *   5xx response, response timeout, connection reset mid-request     → UNKNOWN
 * </pre>
 */
@Component
public class AccountServiceClient {

    private static final Logger log = LoggerFactory.getLogger(AccountServiceClient.class);

    private final AccountApi customerApi;
    private final InternalAccountApi internalApi;
    private final ObjectMapper objectMapper;

    public AccountServiceClient(AccountApi customerApi, InternalAccountApi internalApi, ObjectMapper objectMapper) {
        this.customerApi = customerApi;
        this.internalApi = internalApi;
        this.objectMapper = objectMapper;
    }

    // ---- with the CUSTOMER's token: account-service checks the account is theirs -------------

    /** One of the caller's own accounts (404 if it isn't theirs). */
    public AccountView getOwnAccount(UUID accountId) {
        return call("GET own account", () -> customerApi.getAccount(accountId));
    }

    /** Take money out of the caller's own account. Only possible with the customer's token: their consent. */
    public AccountView debit(UUID accountId, BigDecimal amount) {
        return call("withdraw", () -> customerApi.withdraw(accountId, new MoneyCommand(amount)));
    }

    // ---- with payment-service's OWN token (Client Credentials) ---------------------------------

    /** Any account, by number. Minimal data: no owner, no balance. */
    public AccountView findRecipient(String accountNumber) {
        return call("find recipient", () -> internalApi.findByNumber(accountNumber));
    }

    /** Credit any active account; also used to refund the source when a transfer is reversed. */
    public AccountView credit(UUID accountId, BigDecimal amount, UUID paymentId) {
        return call("credit", () -> internalApi.credit(accountId, new CreditCommand(amount, paymentId)));
    }

    private <T> T call(String operation, Supplier<T> request) {
        try {
            return request.get();
        } catch (RestClientResponseException ex) {
            throw translate(operation, ex);
        } catch (OAuth2AuthorizationException ex) {
            // Couldn't get payment-service's own token (Keycloak down, wrong secret). Thrown BEFORE
            // the request was sent, so account-service certainly did nothing. Full detail only in
            // the log: the failure reason is shown to customers, and Keycloak's message is for operators.
            log.warn("Could not obtain payment-service's access token: {}", ex.getMessage());
            throw new AccountServiceUnavailableException(
                    "account-service %s not attempted: no service token (%s)".formatted(operation, ex.getError().getErrorCode()),
                    false, ex);
        } catch (ResourceAccessException ex) {
            // I/O failure: no HTTP response at all. Did the request ever reach the server?
            boolean neverSent = isConnectionFailure(ex);
            throw new AccountServiceUnavailableException(
                    "account-service %s failed: %s".formatted(operation, neverSent ? "unreachable" : "no response"),
                    !neverSent, ex);
        }
    }

    private RuntimeException translate(String operation, RestClientResponseException ex) {
        int status = ex.getStatusCode().value();
        JsonNode problem = parseProblem(ex.getResponseBodyAsString());
        String detail = problem.path("detail").asText(ex.getStatusText());

        if (status == 404) {
            return new AccountRejectedException(status, "ACCOUNT_NOT_FOUND", detail);
        }
        if (status == 422) {
            return new AccountRejectedException(status, problem.path("code").asText("BUSINESS_RULE_VIOLATION"), detail);
        }
        // Other 4xx (401/403 = our token wasn't accepted, 409 = concurrent update, 400 = our bug):
        // account-service refused the request, so it had no effect. A 5xx says nothing for certain:
        // the error could have happened after the database commit.
        boolean outcomeUnknown = ex.getStatusCode().is5xxServerError();
        return new AccountServiceUnavailableException(
                "account-service %s returned %d: %s".formatted(operation, status, detail), outcomeUnknown, ex);
    }

    private JsonNode parseProblem(String body) {
        try {
            return body == null || body.isBlank() ? objectMapper.createObjectNode() : objectMapper.readTree(body);
        } catch (IOException notJson) {
            return objectMapper.createObjectNode();
        }
    }

    /** True when the TCP connection was never established, so the server can't have seen the request. */
    static boolean isConnectionFailure(Throwable ex) {
        for (Throwable cause = ex; cause != null; cause = cause.getCause()) {
            if (cause instanceof ConnectException
                    || cause instanceof HttpConnectTimeoutException
                    || cause instanceof UnknownHostException
                    || cause instanceof NoRouteToHostException) {
                return true;
            }
        }
        return false;
    }
}
