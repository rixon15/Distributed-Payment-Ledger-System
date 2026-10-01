package org.example.authorizationservice.integration.base;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.localstack.LocalStackContainer;
import org.testcontainers.lifecycle.Startables;
import org.testcontainers.utility.DockerImageName;

@SuppressWarnings("resource")
public abstract class AbstractIntegrationTest {

    static final PostgreSQLContainer<?> POSTGRESQL_CONTAINER = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("auth_db")
            .withUsername("testUser")
            .withPassword("testPass");

    // Signing keys are wrapped with a KMS key at startup, so the context can't load without KMS; same image as
    // docker-compose
    static final LocalStackContainer LOCALSTACK_CONTAINER =
            new LocalStackContainer(DockerImageName.parse("localstack/localstack:4.4.0"))
                    .withServices(LocalStackContainer.Service.KMS);

    static {
        Startables.deepStart(POSTGRESQL_CONTAINER, LOCALSTACK_CONTAINER).join();
    }

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRESQL_CONTAINER::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRESQL_CONTAINER::getUsername);
        registry.add("spring.datasource.password", POSTGRESQL_CONTAINER::getPassword);

        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");

        registry.add("aws.kms.endpoint", () -> LOCALSTACK_CONTAINER.getEndpoint().toString());
        registry.add("aws.region", LOCALSTACK_CONTAINER::getRegion);
    }
}
