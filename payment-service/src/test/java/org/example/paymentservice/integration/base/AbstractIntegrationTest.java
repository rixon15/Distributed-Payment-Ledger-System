package org.example.paymentservice.integration.base;

import org.example.paymentservice.model.PaymentType;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Subclasses share these static containers but must NOT share the Spring context: with
 * identical inherited {@code @DynamicPropertySource} config, Spring's context cache treats
 * them as interchangeable and reuses one ApplicationContext (and its Kafka listener / Redis
 * connection beans) across classes. A message left in-flight by one test class can then be
 * redelivered into the next class's DB/Redis state, causing cross-test contamination.
 * {@code @DirtiesContext} forces a fresh context per class.
 */
@SuppressWarnings("resource")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
public abstract class AbstractIntegrationTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private StringRedisTemplate redisTemplate;

    static final PostgreSQLContainer<?> POSTGRESQL_CONTAINER = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("payment_db")
            .withUsername("testuser")
            .withPassword("testpass");

    static final GenericContainer<?> REDIS_CONTAINER = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    static final KafkaContainer KAFKA_CONTAINER = new KafkaContainer(
            DockerImageName.parse("apache/kafka:3.7.0")
    );

    static {
        POSTGRESQL_CONTAINER.start();
        REDIS_CONTAINER.start();
        KAFKA_CONTAINER.start();
    }

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRESQL_CONTAINER::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRESQL_CONTAINER::getUsername);
        registry.add("spring.datasource.password", POSTGRESQL_CONTAINER::getPassword);

        registry.add("spring.kafka.bootstrap-servers", KAFKA_CONTAINER::getBootstrapServers);

        registry.add("spring.data.redis.host", REDIS_CONTAINER::getHost);
        registry.add("spring.data.redis.port", () -> REDIS_CONTAINER.getMappedPort(6379));

        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");

        registry.add("spring.profiles.active", () -> "test");
    }

    protected void assertOutboxCountBySenderAndType(UUID senderId, PaymentType type, int expected) {

        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM outbox_events WHERE aggregate_id = ? AND event_type = ?",
                Integer.class,
                senderId.toString(),
                type.name()
        );

        assertThat(count).isEqualTo(expected);

    }

    protected void deleteByPrefix(String prefix) {
        var keys = redisTemplate.keys(prefix + "*");
        if (keys != null && !keys.isEmpty()) {
            redisTemplate.delete(keys);
        }
    }

    private void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting", e);
        }
    }

    protected void awaitPaymentCountByIdempotencyKey(String key, int expected, Duration timeout) {
        Instant deadline = Instant.now().plus(timeout);

        while (Instant.now().isBefore(deadline)) {
            Integer count = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM payments WHERE idempotency_key = ?",
                    Integer.class,
                    key
            );

            if (count != null && count == expected) {
                return;
            }
            sleep(100);
        }

        Integer finalCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM payments WHERE idempotency_key = ?",
                Integer.class,
                key
        );

        assertThat(finalCount).isEqualTo(expected);
    }

    protected void assertOutboxCount(int expected) {
        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM outbox_events", Integer.class);
        assertThat(count).isEqualTo(expected);
    }

    protected void awaitPaymentStatusByIdempotencyKey(String key, String expectedStatus, Duration timeout) {
        Instant deadline = Instant.now().plus(timeout);

        while (Instant.now().isBefore(deadline)) {
            String status = jdbcTemplate.query(
                    "SELECT status FROM payments WHERE idempotency_key = ? ORDER BY created_at DESC LIMIT 1",
                    ps -> ps.setString(1, key),
                    rs -> rs.next() ? rs.getString("status") : null
            );

            if (expectedStatus.equals(status)) {
                return;
            }

            sleep(100);
        }

        String finalStatus = jdbcTemplate.query(
                "SELECT status FROM payments WHERE idempotency_key = ? ORDER BY created_at DESC LIMIT 1",
                ps -> ps.setString(1, key),
                rs -> rs.next() ? rs.getString("status") : null
        );

        assertThat(finalStatus).isEqualTo(expectedStatus);
    }

    protected void assertOutboxPayloadStatusBySenderAndType(UUID senderId, PaymentType type, String expectedStatus, int expectedCount) {
        Integer count = jdbcTemplate.queryForObject(
                """
                        SELECT COUNT(*)
                        FROM outbox_events
                        WHERE aggregate_id = ?
                          AND event_type = ?
                          AND payload->'payload'->>'status' = ?
                        """,
                Integer.class,
                senderId.toString(),
                type.name(),
                expectedStatus
        );

        assertThat(count).isEqualTo(expectedCount);
    }
}
