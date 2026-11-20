package com.banking.notificationservice;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.config.TopicBuilder;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

/** A real Kafka broker, with the topics created up front like create-topics.sh does. */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

    @Bean
    @ServiceConnection
    KafkaContainer kafkaContainer() {
        return new KafkaContainer(DockerImageName.parse("apache/kafka:4.1.1"));
    }

    @Bean
    NewTopic paymentEvents() {
        return TopicBuilder.name("banking.payment-events").partitions(3).build();
    }

    @Bean
    NewTopic accountTransactions() {
        return TopicBuilder.name("banking.account-transactions").partitions(3).build();
    }

    @Bean
    NewTopic customerEvents() {
        return TopicBuilder.name("banking.customer-events").partitions(3).compact().build();
    }

    @Bean
    NewTopic paymentEventsDlt() {
        return TopicBuilder.name("banking.payment-events.DLT").partitions(3).build();
    }

    @Bean
    NewTopic accountTransactionsDlt() {
        return TopicBuilder.name("banking.account-transactions.DLT").partitions(3).build();
    }

    @Bean
    NewTopic customerEventsDlt() {
        return TopicBuilder.name("banking.customer-events.DLT").partitions(3).build();
    }
}
