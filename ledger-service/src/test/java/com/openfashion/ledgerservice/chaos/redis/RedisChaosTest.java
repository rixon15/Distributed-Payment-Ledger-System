package com.openfashion.ledgerservice.chaos.redis;

import com.openfashion.ledgerservice.dto.event.TransactionInitiatedEvent;
import com.openfashion.ledgerservice.dto.event.TransactionPayload;
import com.openfashion.ledgerservice.model.*;
import com.openfashion.ledgerservice.repository.AccountRepository;
import com.openfashion.ledgerservice.repository.TransactionRepository;
import com.openfashion.ledgerservice.service.RedisService;
import eu.rekawek.toxiproxy.Proxy;
import eu.rekawek.toxiproxy.ToxiproxyClient;
import eu.rekawek.toxiproxy.model.ToxicDirection;
import io.confluent.parallelconsumer.ParallelStreamProcessor;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.ToxiproxyContainer;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@SuppressWarnings("resource")
class RedisChaosTest {

    private static final String TOPIC = "transaction.request";

    private static final int REDIS_PORT = 6379;
    private static final int TOXIPROXY_REDIS_PORT = 26379;

    private static final String IDEMPOTENCY_KEY = "ledger:idempotency:set";
    private static final String DB_SNAPSHOT_KEY = "ledger:db:snapshot";
    private static final String PENDING_DELTA_KEY = "ledger:pending:delta";
    private static final String DLQ_STREAM_KEY = "ledger:stream:tx:dlq";
    private static final String BATCH_DONE_STREAM = "ledger:stream:batch:done";
    private static final String BATCH_META_PREFIX = "ledger:batch:meta:";

    static final Network NETWORK = Network.newNetwork();

    static final PostgreSQLContainer<?> POSTGRESQL_CONTAINER =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("ledger_db")
                    .withUsername("testuser")
                    .withPassword("testpass")
                    .withNetwork(NETWORK)
                    .withNetworkAliases("postgres");

    static final GenericContainer<?> REDIS_CONTAINER =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
                    .withExposedPorts(REDIS_PORT)
                    .withNetwork(NETWORK)
                    .withNetworkAliases("redis");

    static final KafkaContainer KAFKA_CONTAINER =
            new KafkaContainer(DockerImageName.parse("apache/kafka:3.7.0"))
                    .withNetwork(NETWORK)
                    .withNetworkAliases("kafka");

    static final ToxiproxyContainer TOXIPROXY =
            new ToxiproxyContainer(DockerImageName.parse("ghcr.io/shopify/toxiproxy:2.11.0"))
                    .withNetwork(NETWORK)
                    .withExposedPorts(8474, TOXIPROXY_REDIS_PORT);

    static Proxy redisProxy;

    static {
        POSTGRESQL_CONTAINER.start();
        REDIS_CONTAINER.start();
        KAFKA_CONTAINER.start();
        TOXIPROXY.start();
    }

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRESQL_CONTAINER::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRESQL_CONTAINER::getUsername);
        registry.add("spring.datasource.password", POSTGRESQL_CONTAINER::getPassword);

        registry.add("spring.kafka.bootstrap-servers", KAFKA_CONTAINER::getBootstrapServers);

        registry.add("spring.data.redis.host", TOXIPROXY::getHost);
        registry.add("spring.data.redis.port", () -> TOXIPROXY.getMappedPort(TOXIPROXY_REDIS_PORT));

        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
    }

    @BeforeAll
    static void setupProxy() throws Exception {
        ToxiproxyClient client = new ToxiproxyClient(
                TOXIPROXY.getHost(),
                TOXIPROXY.getControlPort()
        );

        redisProxy = client.createProxy(
                "redis-proxy",
                "0.0.0.0:" + TOXIPROXY_REDIS_PORT,
                "redis:" + REDIS_PORT
        );
    }

    @AfterEach
    void cleanupProxy() throws Exception {
        if (redisProxy != null) {
            redisProxy.enable();
            for (var toxic : redisProxy.toxics().getAll()) {
                toxic.remove();
            }
        }
    }

    @Autowired
    private KafkaTemplate<String, Object> kafkaTemplate;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private TransactionRepository transactionRepository;

    @Autowired
    private RedisService redisService;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired(required = false)
    private ParallelStreamProcessor<String, TransactionInitiatedEvent> parallelConsumer;

    @AfterAll
    void shutdownConsumer() {
        if (parallelConsumer != null) {
            parallelConsumer.close();
        }
    }

    @BeforeEach
    void cleanState() {
        jdbcTemplate.execute("TRUNCATE TABLE postings, outbox_events, transactions, accounts CASCADE");

        redisTemplate.delete(List.of(
                IDEMPOTENCY_KEY,
                DB_SNAPSHOT_KEY,
                PENDING_DELTA_KEY,
                DLQ_STREAM_KEY,
                BATCH_DONE_STREAM
        ));

        Set<String> batchMetaKeys = redisTemplate.keys(BATCH_META_PREFIX + "*");
        if (batchMetaKeys != null && !batchMetaKeys.isEmpty()) {
            redisTemplate.delete(batchMetaKeys);
        }

        // Intentionally do not wipe the main stream key itself.
    }

    @Test
    void redisDown_afterStartup_noPersistenceUntilRecovery_thenProcessesExactlyOnce() throws Exception {
        Seed seed = seedTransferAccounts();

        UUID referenceId = UUID.randomUUID();
        String validEvent = eventJson(
                referenceId,
                TransactionType.TRANSFER,
                seed.userA(),
                seed.userB(),
                "25.0000"
        );

        redisProxy.disable();

        kafkaTemplate.send(TOPIC, referenceId.toString(), validEvent).get();

        Thread.sleep(2_000);

        redisProxy.enable();

        // After Redis becomes reachable again, DB should still be clean before retry/re-drive.
        Awaitility.await()
                .atMost(Duration.ofSeconds(5))
                .untilAsserted(() -> {
                    assertThat(countTransactionsForReference(referenceId)).isZero();
                    assertThat(countPostingsForReference(referenceId)).isZero();
                    assertThat(countOutboxForReference(referenceId)).isZero();
                });

        // Explicit re-drive keeps the test deterministic.
        kafkaTemplate.send(TOPIC, referenceId.toString(), validEvent).get();

        Awaitility.await()
                .atMost(Duration.ofSeconds(45))
                .pollInterval(Duration.ofMillis(500))
                .untilAsserted(() -> {
                    assertThat(countTransactionsForReference(referenceId)).isEqualTo(1);
                    assertThat(countPostingsForReference(referenceId)).isEqualTo(2);
                    assertThat(countOutboxForReference(referenceId)).isEqualTo(1);
                });

        Transaction tx = transactionRepository
                .findByReferenceIdAndType(referenceId, TransactionType.TRANSFER)
                .orElseThrow();

        assertThat(tx.getStatus()).isEqualTo(TransactionStatus.POSTED);
    }

    @Test
    void redisLatency_duringIngress_delaysButDoesNotDuplicate_andEventuallyProcesses() throws Exception {
        Seed seed = seedTransferAccounts();

        UUID referenceId = UUID.randomUUID();
        String validEvent = eventJson(
                referenceId,
                TransactionType.TRANSFER,
                seed.userA(),
                seed.userB(),
                "25.0000"
        );

        redisProxy.toxics().latency("redis-latency", ToxicDirection.DOWNSTREAM, 250);

        kafkaTemplate.send(TOPIC, referenceId.toString(), validEvent).get();

        Awaitility.await()
                .atMost(Duration.ofSeconds(20))
                .pollInterval(Duration.ofMillis(500))
                .untilAsserted(() -> {
                    assertThat(countTransactionsForReference(referenceId)).isLessThanOrEqualTo(1);
                    assertThat(countOutboxForReference(referenceId)).isLessThanOrEqualTo(1);
                });

        redisProxy.toxics().get("redis-latency").remove();

        Awaitility.await()
                .atMost(Duration.ofSeconds(45))
                .pollInterval(Duration.ofMillis(500))
                .untilAsserted(() -> {
                    assertThat(countTransactionsForReference(referenceId)).isEqualTo(1);
                    assertThat(countPostingsForReference(referenceId)).isEqualTo(2);
                    assertThat(countOutboxForReference(referenceId)).isEqualTo(1);
                });

        Transaction tx = transactionRepository
                .findByReferenceIdAndType(referenceId, TransactionType.TRANSFER)
                .orElseThrow();

        assertThat(tx.getStatus()).isEqualTo(TransactionStatus.POSTED);
    }

    private Seed seedTransferAccounts() {
        UUID userA = UUID.randomUUID();
        UUID userB = UUID.randomUUID();

        userAccount(userA, "USER_A", "100.0000");
        userAccount(userB, "USER_B", "50.0000");

        return new Seed(userA, userB);
    }

    private void userAccount(UUID userId, String name, String balance) {
        Account account = new Account();
        account.setUserId(userId);
        account.setName(name);
        account.setType(AccountType.ASSET);
        account.setCurrency(CurrencyType.USD);
        account.setBalance(new BigDecimal(balance));
        account.setStatus(AccountStatus.ACTIVE);

        Account savedAccount = accountRepository.saveAndFlush(account);
        redisService.initializeSnapshotIfMissing(savedAccount);
    }

    private String eventJson(
            UUID referenceId,
            TransactionType eventType,
            UUID senderId,
            UUID receiverId,
            String amount
    ) throws Exception {
        TransactionPayload payload = new TransactionPayload(
                senderId,
                receiverId,
                new BigDecimal(amount),
                CurrencyType.USD,
                TransactionStatus.POSTED,
                "redis-chaos-test",
                Instant.now(),
                Map.of("source", "chaos-test")
        );

        TransactionInitiatedEvent event = new TransactionInitiatedEvent(
                UUID.randomUUID(),
                eventType,
                referenceId,
                Instant.now(),
                payload
        );

        return objectMapper.writeValueAsString(event);
    }

    private Integer countTransactionsForReference(UUID referenceId) {
        return jdbcTemplate.queryForObject(
                """
                        SELECT COUNT(*)
                        FROM transactions
                        WHERE reference_id = ?
                        """,
                Integer.class,
                referenceId
        );
    }

    private Integer countPostingsForReference(UUID referenceId) {
        return jdbcTemplate.queryForObject(
                """
                        SELECT COUNT(*)
                        FROM postings p
                        JOIN transactions t ON p.transaction_id = t.id
                        WHERE t.reference_id = ?
                        """,
                Integer.class,
                referenceId
        );
    }

    private Integer countOutboxForReference(UUID referenceId) {
        return jdbcTemplate.queryForObject(
                """
                        SELECT COUNT(*)
                        FROM outbox_events
                        WHERE payload->>'referenceId' = ?
                        """,
                Integer.class,
                referenceId.toString()
        );
    }

    private record Seed(UUID userA, UUID userB) {
    }

}
