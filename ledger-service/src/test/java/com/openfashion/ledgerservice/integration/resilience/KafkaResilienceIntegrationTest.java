package com.openfashion.ledgerservice.integration.resilience;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openfashion.ledgerservice.integration.base.AbstractIntegrationTest;
import com.openfashion.ledgerservice.model.*;
import com.openfashion.ledgerservice.repository.AccountRepository;
import com.openfashion.ledgerservice.repository.TransactionRepository;
import com.openfashion.ledgerservice.scheduler.RedisProcessor;
import com.openfashion.ledgerservice.service.RedisService;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.shaded.com.fasterxml.jackson.databind.deser.std.StringDeserializer;
import org.testcontainers.shaded.org.awaitility.Awaitility;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class KafkaResilienceIntegrationTest extends AbstractIntegrationTest {

    private static final String TOPIC = "transaction.request";
    private static final String DLQ_TOPIC = "transaction.response.dlq";

    @Value("${spring.kafka.bootstrap-servers}")
    private String bootstrapServers;

    @Autowired
    private KafkaTemplate<String, Object> kafkaTemplate;
    @Autowired
    private AccountRepository accountRepository;
    @Autowired
    private TransactionRepository transactionRepository;
    @Autowired
    private RedisService redisService;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private StringRedisTemplate redisTemplate;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final String IDEMPOTENCY_KEY = "ledger:idempotency:set";
    private static final String DB_SNAPSHOT_KEY = "ledger:db:snapshot";
    private static final String PENDING_DELTA_KEY = "ledger:pending:delta";
    private static final String DLQ_STREAM_KEY = "ledger:stream:tx:dlq";
    private static final String BATCH_DONE_STREAM = "ledger:stream:batch:done";
    private static final String BATCH_META_PREFIX = "ledger:batch:meta:";

    @BeforeEach
    void cleanDbOnly() {
        // Keep Redis cleanup optional here; DB reset is the key invariant.
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

        // Intentionally DO NOT delete ledger:stream:tx
        // because that also removes the consumer group created at startup.
    }


    //isIgnored: It is ignored from the point of processing the malformed JSON
    @Test
    void malformedJson_isIgnoredAndNextValidEventStillProcesses() throws Exception {
        Seed seed = seedTransferAccounts();

        String malformed = "{\"eventId\": \"not-even-complete\"";
        kafkaTemplate.send(TOPIC, UUID.randomUUID().toString(), malformed);

        UUID goodReef = UUID.randomUUID();
        String valid = eventJson(
                goodReef, "TRANSFER", seed.userA, seed.userB, "25.0000"
        );
        kafkaTemplate.send(TOPIC, goodReef.toString(), valid);

        Awaitility.await()
                .atMost(Duration.ofSeconds(20))
                .untilAsserted(() -> assertThat(transactionRepository.findByReferenceIdAndType(goodReef, TransactionType.TRANSFER))
                        .isPresent()
                        .get()
                        .extracting(Transaction::getStatus)
                        .isEqualTo(TransactionStatus.POSTED));

        Integer txCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM transactions", Integer.class);
        assertThat(txCount).isEqualTo(1);

    }

    @Test
    void unsupportedMappedType_feeEvent_noBalanceMutationAndPipelineContinues() throws Exception {
        Seed seed = seedTransferAccounts();

        UUID unsupportedRef = UUID.randomUUID();
        String unsupported = eventJson(
                unsupportedRef, "FEE", seed.userA, seed.userB, "10.0000"
        );
        kafkaTemplate.send(TOPIC, unsupportedRef.toString(), unsupported).get();

        UUID goodRef = UUID.randomUUID();
        String valid = eventJson(
                goodRef, "TRANSFER", seed.userA, seed.userB, "25.0000"
        );
        kafkaTemplate.send(TOPIC, goodRef.toString(), valid).get();

        Awaitility.await()
                .atMost(Duration.ofSeconds(20))
                .untilAsserted(() -> assertThat(transactionRepository.findByReferenceIdAndType(goodRef, TransactionType.TRANSFER))
                        .isPresent());

        Integer unsupportedTx = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM transactions WHERE reference_id = ?",
                Integer.class,
                unsupportedRef
        );

        assertThat(unsupportedTx).isZero();
    }

    @Test
    void unknownEnumType_deserializationFailureAndNextValidStillProcesses() throws Exception {
        Seed seed = seedTransferAccounts();

        UUID unknownRef = UUID.randomUUID();
        String unknownEnumJson = """
                {
                  "eventId": "%s",
                  "eventType": "UNKNOWN_STUFF",
                  "aggregatedId": "%s",
                  "timestamp": "%s",
                  "payload": {
                    "senderId": "%s",
                    "receiverId": "%s",
                    "amount": 10.0000,
                    "currency": "USD",
                    "status": "POSTED",
                    "userMessage": "resilience-test",
                    "timestamp": "%s",
                    "metadata": {"source":"integration-test"}
                  }
                }
                """.formatted(
                UUID.randomUUID(),
                unknownRef,
                Instant.now(),
                seed.userA,
                seed.userB,
                Instant.now()
        );
        kafkaTemplate.send(TOPIC, unknownRef.toString(), unknownEnumJson).get();

        UUID goodRef = UUID.randomUUID();
        String validJson = eventJson(
                goodRef, "TRANSFER", seed.userA, seed.userB, "25.0000"
        );
        kafkaTemplate.send(TOPIC, goodRef.toString(), validJson).get();

        Awaitility.await()
                .atMost(Duration.ofSeconds(20))
                .untilAsserted(() -> assertThat(transactionRepository.findByReferenceIdAndType(goodRef, TransactionType.TRANSFER))
                        .isPresent());

        Integer unknownTx = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM transactions WHERE reference_id = ?",
                Integer.class,
                unknownRef
        );

        assertThat(unknownTx).isZero();
    }

    @Test
    void malformedJson_routesToKafkaDlq_andMainFlowContinues() throws Exception {
        Seed seed = seedTransferAccounts();

        String malformed = "{\"eventId\": \"not-even-complete\"";
        UUID goodReef = UUID.randomUUID();
        String valid = eventJson(
                goodReef, "TRANSFER", seed.userA, seed.userB, "25.0000"
        );

        try (Consumer<String, String> dlqConsumer = newDlqConsumer("it-dlq-" + UUID.randomUUID())) {

            kafkaTemplate.send(TOPIC, UUID.randomUUID().toString(), malformed).get();

        }
    }

    private Consumer<String, String> newDlqConsumer(String groupId) {
        Map<String, Object> props = Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers,
                ConsumerConfig.GROUP_ID_CONFIG, groupId,
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, true,
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class
        );

        Consumer<String, String> consumer = new KafkaConsumer<>(props);
        consumer.subscribe(List.of(DLQ_TOPIC));

        consumer.poll(Duration.ofMillis(500));
        return consumer;
    }

    private ConsumerRecord<String, String> awaitDlqRecord(
            Consumer<String, String> consumer,
            Duration timeout
    ) {
        long deadline = System.nanoTime() + timeout.toNanos();

        while (System.nanoTime() < deadline) {
            for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofMillis(500))) {
                return record;
            }
        }

        return null;
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
            String eventType,
            UUID senderId,
            UUID receiverId,
            String amount
    ) throws Exception {
        Map<String, Object> event = Map.of(
                "eventId", UUID.randomUUID(),
                "eventType", eventType,
                "aggregatedId", referenceId,
                "timestamp", Instant.now().toString(),
                "payload", Map.of(
                        "senderId", senderId,
                        "receiverId", receiverId,
                        "amount", amount,
                        "currency", "USD",
                        "status", "POSTED",
                        "userMessage", "resilience-test",
                        "timestamp", Instant.now().toString(),
                        "metadata", Map.of("source", "integration-test")
                )
        );
        return objectMapper.writeValueAsString(event);
    }

    private record Seed(UUID userA, UUID userB) {
    }

}
