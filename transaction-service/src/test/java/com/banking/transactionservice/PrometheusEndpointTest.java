package com.banking.transactionservice;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Phase 14: Prometheus scrapes without a token, and every metric says which service it came from. */
@SpringBootTest(properties = "management.otlp.tracing.endpoint=http://localhost:1/v1/traces")
@AutoConfigureObservability
@Import(TestcontainersConfiguration.class)
class PrometheusEndpointTest {

    @Autowired
    private WebApplicationContext context;

    @Test
    void metricsAreScrapableWithoutATokenAndTaggedWithTheService() throws Exception {
        MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build()
                .perform(get("/actuator/prometheus"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("jvm_memory_used_bytes{application=\"transaction-service\"")));
    }

    @Test
    void theOtherActuatorEndpointsAreNotOpened() throws Exception {
        MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build()
                .perform(get("/actuator/metrics"))
                .andExpect(status().is4xxClientError());   // not exposed (404) or not allowed (401)
    }
}
