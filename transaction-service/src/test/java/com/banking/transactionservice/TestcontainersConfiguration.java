package com.banking.transactionservice;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.kafka.config.TopicBuilder;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

/** Real PostgreSQL and Kafka (with its topics) for integration tests. See user-service's copy for the explanation. */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

    @Bean
    @ServiceConnection
    PostgreSQLContainer<?> postgresContainer() {
        return new PostgreSQLContainer<>(DockerImageName.parse("postgres:17.12-alpine"))
                .withDatabaseName("transaction_db");
    }

    /** Same image as docker-compose. @ServiceConnection provides KafkaConnectionDetails for Spring's clients. */
    @Bean
    @ServiceConnection
    KafkaContainer kafkaContainer() {
        return new KafkaContainer(DockerImageName.parse("apache/kafka:4.1.1"));
    }

    /**
     * Topics exist BEFORE anyone uses them, like infrastructure/kafka/create-topics.sh does for
     * docker-compose. (Spring's KafkaAdmin creates NewTopic beans at startup.) Otherwise the DLT
     * would only appear with the first dead letter, and a consumer subscribed earlier may not see
     * it for minutes (metadata refresh).
     */
    @Bean
    NewTopic accountTransactionsTopic() {
        return TopicBuilder.name("banking.account-transactions").partitions(3).build();
    }

    @Bean
    NewTopic accountTransactionsDeadLetterTopic() {
        return TopicBuilder.name("banking.account-transactions.DLT").partitions(3).build();
    }
}
