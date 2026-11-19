package com.banking.gateway.ratelimit;

import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.net.InetSocketAddress;
import java.security.Principal;

@Configuration
public class RateLimitKeyConfig {

    /**
     * Who is rate limited?
     * <ul>
     *   <li>Authenticated: the user ({@code sub}). Every user gets their own budget, even when
     *       many users share one IP (an office NAT, a mobile carrier).</li>
     *   <li>Otherwise: the client IP. Note: behind a load balancer the remote address is the LB.
     *       Then configure trusted X-Forwarded-For handling, or limit at the LB.</li>
     * </ul>
     * The rate-limit filter runs after JWT validation, so on /api/** the user key is the normal case.
     */
    @Bean
    KeyResolver userOrIpKeyResolver() {
        return exchange -> exchange.getPrincipal()
                .map(Principal::getName)
                .map(name -> "user:" + name)
                .switchIfEmpty(Mono.fromSupplier(() -> "ip:" + clientIp(exchange)));
    }

    private static String clientIp(ServerWebExchange exchange) {
        InetSocketAddress remote = exchange.getRequest().getRemoteAddress();
        return remote != null && remote.getAddress() != null ? remote.getAddress().getHostAddress() : "unknown";
    }
}
