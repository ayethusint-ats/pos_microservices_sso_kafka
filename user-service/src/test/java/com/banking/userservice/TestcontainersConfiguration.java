package com.banking.userservice;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Starts a real PostgreSQL in Docker for integration tests.
 *
 * <p>{@code @ServiceConnection} makes Spring Boot point the DataSource at this container
 * (URL, username, password), overriding application.yml. No .env file is needed in tests.
 * Use the same image version as docker-compose.yml so tests match the real database.
 * Spring caches the test context, so all test classes with the same setup share one container.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

    @Bean
    @ServiceConnection
    PostgreSQLContainer<?> postgresContainer() {
        return new PostgreSQLContainer<>(DockerImageName.parse("postgres:17.12-alpine"))
                .withDatabaseName("user_db");
    }
}
