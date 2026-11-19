package com.banking.paymentservice.client;

import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.io.IOException;

/**
 * Token relay: forwards the CALLER's access token to account-service.
 *
 * <p>account-service then sees the same user (same {@code sub}, same roles) and applies its own
 * ownership rules. payment-service can't do anything the customer couldn't do directly.
 * That's the strength of token relay, and also its limit:
 * <ul>
 *   <li>Alice's token can't credit Bob's account, so transfers to OTHER customers are impossible
 *       (Phase 10 adds payment-service's own identity, Client Credentials, for that).</li>
 *   <li>Work that runs after the request (Kafka consumers, schedulers, retries) has no user token.</li>
 *   <li>Every downstream service must accept the user's token (the audience grows).</li>
 * </ul>
 *
 * <p>The token is read from the SecurityContext of the current request thread. It's available
 * because the call happens synchronously while the request is still being handled.
 */
public class BearerTokenRelayInterceptor implements ClientHttpRequestInterceptor {

    @Override
    public ClientHttpResponse intercept(HttpRequest request, byte[] body, ClientHttpRequestExecution execution)
            throws IOException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication instanceof JwtAuthenticationToken jwtAuthentication) {
            request.getHeaders().setBearerAuth(jwtAuthentication.getToken().getTokenValue());
        }
        // No user token → no header: account-service answers 401, which we report as a failure.
        return execution.execute(request, body);
    }
}
