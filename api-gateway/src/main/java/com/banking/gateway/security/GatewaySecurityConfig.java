package com.banking.gateway.security;

import com.banking.gateway.config.GatewayProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.ReactiveJwtAuthenticationConverterAdapter;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.reactive.CorsConfigurationSource;
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource;

import java.time.Duration;
import java.util.Collection;
import java.util.List;

/**
 * Security at the edge. The gateway validates tokens exactly like the services do (same issuer,
 * audience and signature rules), so invalid traffic is rejected before it costs any service
 * a thread, a DB connection, or a log line.
 *
 * <p>The gateway does NOT make business authorization decisions ("may alice read account X?").
 * Only the owning service has the data to decide that. Gateway rules stay coarse:
 * authenticated or not, plus admin-only actuator.
 *
 * <p>The validated token is forwarded unchanged ({@code Authorization} header passes through),
 * and each service validates it again (zero trust).
 */
@Configuration
@EnableWebFluxSecurity
public class GatewaySecurityConfig {

    @Bean
    SecurityWebFilterChain securityWebFilterChain(ServerHttpSecurity http, ObjectMapper objectMapper) {
        http
                .csrf(ServerHttpSecurity.CsrfSpec::disable)           // bearer tokens, no cookies
                .cors(Customizer.withDefaults())                       // uses the CorsConfigurationSource bean
                .httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
                .formLogin(ServerHttpSecurity.FormLoginSpec::disable)
                .logout(ServerHttpSecurity.LogoutSpec::disable)
                .requestCache(ServerHttpSecurity.RequestCacheSpec::disable)
                .authorizeExchange(exchanges -> exchanges
                        // CORS preflight carries no token by design.
                        .pathMatchers(HttpMethod.OPTIONS, "/api/**").permitAll()
                        .pathMatchers("/actuator/health", "/actuator/health/**", "/actuator/info").permitAll()
                        // Phase 14: Prometheus scrapes without a token. Metrics hold no customer data; the gateway never
                        // routes /actuator, and in Kubernetes only the monitoring namespace may reach it (Phase 16).
                        .pathMatchers("/actuator/prometheus").permitAll()
                        .pathMatchers("/actuator/**").hasRole("ADMIN")
                        // Same coarse rule as in the services: the back office is staff-only.
                        // Fine-grained rules (SUPPORT read vs ADMIN write, ownership) stay in the services.
                        .pathMatchers("/api/admin/**").hasAnyRole("SUPPORT", "ADMIN")
                        .pathMatchers("/api/**").authenticated()
                        // Allow-list: anything not explicitly exposed is refused.
                        .anyExchange().denyAll())
                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(reactiveJwtAuthenticationConverter()))
                        .authenticationEntryPoint(ReactiveSecurityErrorHandlers.entryPoint(objectMapper))
                        .accessDeniedHandler(ReactiveSecurityErrorHandlers.accessDeniedHandler(objectMapper))
                        .authenticationFailureHandler(ReactiveSecurityErrorHandlers.failureHandler(objectMapper)))
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(ReactiveSecurityErrorHandlers.entryPoint(objectMapper))
                        .accessDeniedHandler(ReactiveSecurityErrorHandlers.accessDeniedHandler(objectMapper)));
        return http.build();
    }

    @Bean
    ReactiveJwtDecoder reactiveJwtDecoder(JwtProperties properties) {
        NimbusReactiveJwtDecoder decoder = NimbusReactiveJwtDecoder.withJwkSetUri(properties.jwkSetUri()).build();
        decoder.setJwtValidator(tokenValidator(properties.issuerUri(), properties.audience()));
        return decoder;
    }

    static OAuth2TokenValidator<Jwt> tokenValidator(String issuer, String audience) {
        return new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(issuer),
                new JwtClaimValidator<Collection<String>>(JwtClaimNames.AUD, aud -> aud != null && aud.contains(audience)));
    }

    private static ReactiveJwtAuthenticationConverterAdapter reactiveJwtAuthenticationConverter() {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(new KeycloakRealmRoleConverter());
        converter.setPrincipalClaimName(JwtClaimNames.SUB);
        return new ReactiveJwtAuthenticationConverterAdapter(converter);
    }

    /**
     * CORS: the browser asks "may JavaScript from http://localhost:4200 call this API?".
     * Configured ONLY here, at the single entry point. If services also added CORS headers,
     * browsers would see duplicates and reject the response.
     */
    @Bean
    CorsConfigurationSource corsConfigurationSource(GatewayProperties properties) {
        CorsConfiguration cors = new CorsConfiguration();
        cors.setAllowedOrigins(properties.cors().allowedOrigins());       // exact origins, never "*" for an API with auth
        cors.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        cors.setAllowedHeaders(List.of("Authorization", "Content-Type", "Idempotency-Key", "X-Request-Id"));
        cors.setExposedHeaders(List.of("Location", "X-Request-Id", "X-Trace-Id", "Retry-After",
                "X-RateLimit-Remaining", "X-RateLimit-Burst-Capacity", "X-RateLimit-Replenish-Rate"));
        cors.setAllowCredentials(false);                                  // tokens are in headers, not cookies
        cors.setMaxAge(Duration.ofHours(1));                              // browsers cache the preflight answer

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", cors);
        return source;
    }
}
