package com.banking.userservice.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import org.springframework.security.oauth2.server.resource.web.BearerTokenAuthenticationEntryPoint;
import org.springframework.security.web.AuthenticationEntryPoint;

import java.io.IOException;
import java.net.URI;

/**
 * 401 Unauthorized: no token, or the token failed validation.
 *
 * <p>Spring's {@link BearerTokenAuthenticationEntryPoint} sets the status and the standard
 * (RFC 6750) {@code WWW-Authenticate: Bearer error="invalid_token", error_description="..."} header.
 * We add an RFC 9457 problem+json body, so clients get the same error format as for 400/404.
 * The body is generic on purpose; the precise reason is in the header and the logs.
 */
public class ProblemDetailsAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final BearerTokenAuthenticationEntryPoint delegate = new BearerTokenAuthenticationEntryPoint();
    private final ObjectMapper objectMapper;

    public ProblemDetailsAuthenticationEntryPoint(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
                         AuthenticationException exception) throws IOException {
        delegate.commence(request, response, exception);

        boolean tokenPresent = exception instanceof InvalidBearerTokenException
                || request.getHeader("Authorization") != null;
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED,
                tokenPresent ? "The access token is invalid or expired" : "An access token is required");
        problem.setTitle("Unauthorized");
        problem.setInstance(URI.create(request.getRequestURI()));

        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        objectMapper.writeValue(response.getOutputStream(), problem);
    }
}
