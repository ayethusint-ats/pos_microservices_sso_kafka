package com.banking.paymentservice.client;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.support.RestClientAdapter;
import org.springframework.web.service.invoker.HttpServiceProxyFactory;

import java.net.http.HttpClient;

/**
 * Wires the declarative {@link AccountApi} onto a {@link RestClient}.
 *
 * <pre>
 *   AccountApi (interface) ──proxy──► RestClient ──► BearerTokenRelayInterceptor ──► JDK HttpClient ──► account-service
 *                                       (JSON)         (adds the caller's token)       (timeouts)
 * </pre>
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AccountServiceProperties.class)
public class AccountClientConfig {

    /**
     * Spring Boot's {@code RestClient.Builder} already has the JSON converters (and, in Phase 14,
     * the tracing instrumentation), so we start from it rather than {@code RestClient.create()}.
     */
    @Bean
    AccountApi accountApi(RestClient.Builder restClientBuilder, AccountServiceProperties properties) {
        return createAccountApi(restClientBuilder, properties);
    }

    /** Static so unit tests can build exactly the production client against a stub server. */
    public static AccountApi createAccountApi(RestClient.Builder restClientBuilder, AccountServiceProperties properties) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(properties.connectTimeout())
                // Plain HTTP/1.1 between services; avoids the h2c upgrade negotiation.
                .version(HttpClient.Version.HTTP_1_1)
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(properties.readTimeout());

        RestClient restClient = restClientBuilder
                .baseUrl(properties.baseUrl().toString())
                .requestFactory(requestFactory)
                .requestInterceptor(new BearerTokenRelayInterceptor())
                .build();

        return HttpServiceProxyFactory.builderFor(RestClientAdapter.create(restClient))
                .build()
                .createClient(AccountApi.class);
    }
}
