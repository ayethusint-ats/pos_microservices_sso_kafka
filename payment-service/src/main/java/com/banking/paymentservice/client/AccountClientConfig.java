package com.banking.paymentservice.client;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.oauth2.client.AuthorizedClientServiceOAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientProviderBuilder;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.client.OAuth2ClientHttpRequestInterceptor;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.support.RestClientAdapter;
import org.springframework.web.service.invoker.HttpServiceProxyFactory;

import java.net.http.HttpClient;

/**
 * Two HTTP clients to the same account-service, with two different identities:
 *
 * <pre>
 *   AccountApi         ──► RestClient + BearerTokenRelayInterceptor     ──► Authorization: Bearer &lt;customer token&gt;
 *   InternalAccountApi ──► RestClient + OAuth2ClientHttpRequestInterceptor ──► Authorization: Bearer &lt;payment-service token&gt;
 *                                          │
 *                                          └─ OAuth2AuthorizedClientManager: gets a token from Keycloak
 *                                             (client_credentials), caches it, renews it before expiry
 * </pre>
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AccountServiceProperties.class)
public class AccountClientConfig {

    /** Registration ID in spring.security.oauth2.client.registration.* */
    public static final String REGISTRATION_ID = "account-service";

    /**
     * Whose token is it? For Client Credentials the answer is always "this application", whichever
     * customer's request we're serving. Without this, the interceptor would use the current user
     * (Alice, Bob, ...) as the cache key and fetch a NEW service token for every customer.
     */
    static final Authentication SERVICE_PRINCIPAL = new AnonymousAuthenticationToken(
            "payment-service", "payment-service", AuthorityUtils.createAuthorityList("ROLE_SERVICE"));

    @Bean
    AccountApi accountApi(RestClient.Builder restClientBuilder, AccountServiceProperties properties) {
        return createAccountApi(restClientBuilder, properties);
    }

    @Bean
    InternalAccountApi internalAccountApi(RestClient.Builder restClientBuilder, AccountServiceProperties properties,
                                          OAuth2AuthorizedClientManager authorizedClientManager,
                                          OAuth2AuthorizedClientService authorizedClientService) {
        return createInternalAccountApi(restClientBuilder, properties, authorizedClientManager, authorizedClientService);
    }

    /**
     * The manager for code that runs OUTSIDE a browser session: it stores tokens in an
     * {@link OAuth2AuthorizedClientService} (in memory) rather than in an HTTP session.
     * The provider knows one grant only: client_credentials (there's no user and no refresh token).
     */
    @Bean
    OAuth2AuthorizedClientManager authorizedClientManager(ClientRegistrationRepository registrations,
                                                          OAuth2AuthorizedClientService authorizedClientService) {
        AuthorizedClientServiceOAuth2AuthorizedClientManager manager =
                new AuthorizedClientServiceOAuth2AuthorizedClientManager(registrations, authorizedClientService);
        manager.setAuthorizedClientProvider(OAuth2AuthorizedClientProviderBuilder.builder().clientCredentials().build());
        return manager;
    }

    /** Customer token relay (Phase 9). Static so unit tests build exactly the production client. */
    public static AccountApi createAccountApi(RestClient.Builder restClientBuilder, AccountServiceProperties properties) {
        return proxy(restClientBuilder, properties, new BearerTokenRelayInterceptor(), AccountApi.class);
    }

    /** payment-service's own identity (Phase 10). */
    public static InternalAccountApi createInternalAccountApi(RestClient.Builder restClientBuilder,
                                                              AccountServiceProperties properties,
                                                              OAuth2AuthorizedClientManager authorizedClientManager,
                                                              OAuth2AuthorizedClientService authorizedClientService) {
        OAuth2ClientHttpRequestInterceptor serviceToken = new OAuth2ClientHttpRequestInterceptor(authorizedClientManager);
        serviceToken.setClientRegistrationIdResolver(request -> REGISTRATION_ID);
        serviceToken.setPrincipalResolver(request -> SERVICE_PRINCIPAL);
        // If account-service answers 401 (e.g. token revoked, Keycloak keys rotated), forget the cached
        // token so the next call fetches a fresh one instead of failing until it expires.
        serviceToken.setAuthorizationFailureHandler(
                OAuth2ClientHttpRequestInterceptor.authorizationFailureHandler(authorizedClientService));
        return proxy(restClientBuilder, properties, serviceToken, InternalAccountApi.class);
    }

    private static <T> T proxy(RestClient.Builder restClientBuilder, AccountServiceProperties properties,
                               ClientHttpRequestInterceptor authentication, Class<T> api) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(properties.connectTimeout())
                // Plain HTTP/1.1 between services; avoids the h2c upgrade negotiation.
                .version(HttpClient.Version.HTTP_1_1)
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(properties.readTimeout());

        // clone(): the injected builder is shared; each client gets its own interceptor list.
        RestClient restClient = restClientBuilder.clone()
                .baseUrl(properties.baseUrl().toString())
                .requestFactory(requestFactory)
                .requestInterceptor(authentication)
                .build();

        return HttpServiceProxyFactory.builderFor(RestClientAdapter.create(restClient)).build().createClient(api);
    }
}
