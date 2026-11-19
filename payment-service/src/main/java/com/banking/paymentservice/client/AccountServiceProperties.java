package com.banking.paymentservice.client;

import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import java.net.URI;
import java.time.Duration;

/**
 * Where account-service is and how long we wait for it. Bound from {@code banking.clients.account-service.*}.
 *
 * @param baseUrl        account-service's own address. Service-to-service calls go DIRECTLY to
 *                       the service, not back out through the public gateway.
 * @param connectTimeout max time to open the TCP connection. If it expires, the request was
 *                       certainly never processed.
 * @param readTimeout    max time to wait for the response. If it expires, the request MAY
 *                       have been processed: we just don't know.
 */
@Validated
@ConfigurationProperties(prefix = "banking.clients.account-service")
public record AccountServiceProperties(
        @NotNull URI baseUrl,
        @DefaultValue("1s") Duration connectTimeout,
        @DefaultValue("2s") Duration readTimeout
) {
}
