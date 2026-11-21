package com.banking.userservice.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.DispatcherType;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.ObjectPostProcessor;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;

import java.util.Collection;

/**
 * Turns this service into an OAuth2 Resource Server.
 *
 * <p>For every request to {@code /api/**} the filter chain:
 * <ol>
 *   <li>reads {@code Authorization: Bearer <jwt>} (no token → 401)</li>
 *   <li>verifies the signature with Keycloak's public key, chosen by the token's {@code kid}
 *       (keys downloaded once from the JWKS endpoint, then cached)</li>
 *   <li>validates {@code exp}/{@code nbf}, {@code iss} and {@code aud} (any failure → 401)</li>
 *   <li>converts the claims into an Authentication with ROLE_* authorities</li>
 * </ol>
 * The service never calls Keycloak per request. Validation is local, fast, and keeps working
 * while Keycloak is briefly down.
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity   // activates @PreAuthorize (and our meta-annotations built on it)
@EnableConfigurationProperties(JwtProperties.class)
public class SecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, ObjectMapper objectMapper) throws Exception {
        ProblemDetailsAuthenticationEntryPoint entryPoint = new ProblemDetailsAuthenticationEntryPoint(objectMapper);
        TokenValidationFailureHandler failureHandler = new TokenValidationFailureHandler(entryPoint, objectMapper);

        http
                // CSRF attacks abuse cookies the browser sends automatically. We authenticate with a
                // header the attacker's page can't set, and keep no session, so CSRF doesn't apply.
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                // Layer 1 of authorization: coarse URL rules. Layer 2 is @PreAuthorize on each
                // controller method (see Authorization). Layer 3 is ownership in the service layer.
                .authorizeHttpRequests(auth -> auth
                        // Error rendering (e.g. Spring's /error dispatch) must not be blocked.
                        .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                        // Liveness/readiness probes (Docker, Kubernetes) must work without a token.
                        .requestMatchers("/actuator/health", "/actuator/health/**", "/actuator/info").permitAll()
                        // Phase 14: Prometheus scrapes without a token. Metrics hold no customer data; the gateway never
                        // routes /actuator, and in Kubernetes only the monitoring namespace may reach it (Phase 16).
                        .requestMatchers("/actuator/prometheus").permitAll()
                        // Back office: staff only, whatever the method-level rules say.
                        .requestMatchers("/api/admin/**").hasAnyRole("SUPPORT", "ADMIN")
                        .requestMatchers("/api/**").authenticated()
                        // Allow-list: everything else is refused, even with a valid token.
                        .anyRequest().denyAll())
                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter()))
                        .authenticationEntryPoint(entryPoint)
                        .accessDeniedHandler(new ProblemDetailsAccessDeniedHandler(objectMapper))
                        // The DSL has no setter for the bearer filter's failure handler, so we
                        // customize the filter object right after Spring creates it.
                        .withObjectPostProcessor(new ObjectPostProcessor<BearerTokenAuthenticationFilter>() {
                            @Override
                            public <O extends BearerTokenAuthenticationFilter> O postProcess(O filter) {
                                filter.setAuthenticationFailureHandler(failureHandler);
                                return filter;
                            }
                        }));
        return http.build();
    }

    /**
     * We build the decoder ourselves (instead of only setting Boot properties) so every
     * validation rule is visible in code. Nimbus downloads the JWKS lazily on the first token,
     * so the service starts even if Keycloak isn't up yet.
     */
    @Bean
    JwtDecoder jwtDecoder(JwtProperties properties) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(properties.jwkSetUri()).build();
        decoder.setJwtValidator(tokenValidator(properties.issuerUri(), properties.audience()));
        return decoder;
    }

    /**
     * Signature aside, what makes a token acceptable here:
     * not expired (60 s clock-skew tolerance), issued by OUR realm, and meant for OUR API.
     */
    static OAuth2TokenValidator<Jwt> tokenValidator(String issuer, String audience) {
        OAuth2TokenValidator<Jwt> defaultsWithIssuer = JwtValidators.createDefaultWithIssuer(issuer);
        OAuth2TokenValidator<Jwt> audienceValidator = new JwtClaimValidator<Collection<String>>(
                JwtClaimNames.AUD, aud -> aud != null && aud.contains(audience));
        return new DelegatingOAuth2TokenValidator<>(defaultsWithIssuer, audienceValidator);
    }

    /** Principal name = {@code sub} (the Keycloak user ID); authorities = scopes + realm roles. */
    @Bean
    JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(new KeycloakRealmRoleConverter());
        converter.setPrincipalClaimName(JwtClaimNames.SUB);
        return converter;
    }
}
