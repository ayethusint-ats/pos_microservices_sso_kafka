package com.banking.paymentservice.support;

import com.banking.paymentservice.client.AccountClientConfig;
import com.banking.paymentservice.client.AccountServiceClient;
import com.banking.paymentservice.client.AccountServiceProperties;
import com.banking.paymentservice.client.AccountServiceResilience;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.security.oauth2.client.AuthorizedClientServiceOAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.InMemoryOAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientProviderBuilder;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.time.Duration;

/** The production client wiring, outside Spring: the OAuth2 pieces are what Boot builds from YAML. */
public final class TestAccountClients {

    private TestAccountClients() {
    }

    public static AccountServiceClient build(String accountServiceUrl, String tokenUri, AccountServiceResilience resilience) {
        AccountServiceProperties properties = new AccountServiceProperties(
                URI.create(accountServiceUrl), Duration.ofMillis(500), Duration.ofMillis(300));

        ClientRegistration registration = ClientRegistration.withRegistrationId(AccountClientConfig.REGISTRATION_ID)
                .clientId("payment-service")
                .clientSecret("test-secret")
                .authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS)
                .tokenUri(tokenUri)
                .build();
        InMemoryClientRegistrationRepository registrations = new InMemoryClientRegistrationRepository(registration);
        InMemoryOAuth2AuthorizedClientService authorizedClients = new InMemoryOAuth2AuthorizedClientService(registrations);
        AuthorizedClientServiceOAuth2AuthorizedClientManager manager =
                new AuthorizedClientServiceOAuth2AuthorizedClientManager(registrations, authorizedClients);
        manager.setAuthorizedClientProvider(OAuth2AuthorizedClientProviderBuilder.builder().clientCredentials().build());

        return new AccountServiceClient(
                AccountClientConfig.createAccountApi(RestClient.builder(), properties),
                AccountClientConfig.createInternalAccountApi(RestClient.builder(), properties, manager, authorizedClients),
                new ObjectMapper(), resilience);
    }
}
