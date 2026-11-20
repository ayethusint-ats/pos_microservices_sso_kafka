package com.banking.transactionservice.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;

import java.io.IOException;
import java.net.URI;

/**
 * Decides what a failed bearer-token check means for the client.
 *
 * <ul>
 *   <li><b>The token is bad</b> (bad signature, expired, wrong iss/aud) → 401. The client should
 *       get a new token.</li>
 *   <li><b>We couldn't check it</b>, e.g. Keycloak's JWKS endpoint is unreachable and no keys are
 *       cached → 503 + Retry-After. The token may be fine. Answering 401 would send users to a
 *       login page that can't work either, and hide an outage behind "auth errors" in the metrics.</li>
 * </ul>
 */
public class TokenValidationFailureHandler implements AuthenticationFailureHandler {

    private static final Logger log = LoggerFactory.getLogger(TokenValidationFailureHandler.class);

    private final AuthenticationEntryPoint unauthorizedEntryPoint;
    private final ObjectMapper objectMapper;

    public TokenValidationFailureHandler(AuthenticationEntryPoint unauthorizedEntryPoint, ObjectMapper objectMapper) {
        this.unauthorizedEntryPoint = unauthorizedEntryPoint;
        this.objectMapper = objectMapper;
    }

    @Override
    public void onAuthenticationFailure(HttpServletRequest request, HttpServletResponse response,
                                        AuthenticationException exception) throws IOException, ServletException {
        if (!(exception instanceof AuthenticationServiceException)) {
            unauthorizedEntryPoint.commence(request, response, exception);
            return;
        }

        log.warn("Cannot validate access tokens, identity provider unavailable: {}", exception.getMessage());
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE,
                "Token validation is temporarily unavailable. Please retry shortly.");
        problem.setTitle("Service unavailable");
        problem.setInstance(URI.create(request.getRequestURI()));

        response.setStatus(HttpStatus.SERVICE_UNAVAILABLE.value());
        response.setHeader(HttpHeaders.RETRY_AFTER, "10");
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        objectMapper.writeValue(response.getOutputStream(), problem);
    }
}
