package com.openfashion.ledgerservice.contract;

import au.com.dius.pact.consumer.dsl.DslPart;
import au.com.dius.pact.consumer.dsl.PactBuilder;
import au.com.dius.pact.consumer.dsl.PactDslJsonBody;
import au.com.dius.pact.consumer.junit5.PactConsumerTestExt;
import au.com.dius.pact.consumer.junit5.PactTestFor;
import au.com.dius.pact.consumer.junit5.ProviderType;
import au.com.dius.pact.core.model.V4Interaction;
import au.com.dius.pact.core.model.V4Pact;
import au.com.dius.pact.core.model.annotations.Pact;
import au.com.dius.pact.core.model.annotations.PactDirectory;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.openfashion.ledgerservice.dto.event.TransactionInitiatedEvent;
import com.openfashion.ledgerservice.model.CurrencyType;
import com.openfashion.ledgerservice.model.TransactionStatus;
import com.openfashion.ledgerservice.model.TransactionType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.Arrays;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(PactConsumerTestExt.class)
@PactTestFor(providerName = "payment-service", providerType = ProviderType.ASYNCH)
@PactDirectory("src/test/resources/pacts")
class TransactionEventConsumerContractTest {

    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());

    @Pact(consumer = "ledger-service")
    public V4Pact validTransactionEvent(PactBuilder builder) { // Use V4Pact and PactBuilder

        DslPart payloadBody = new PactDslJsonBody()
                .uuid("senderId")
                .uuid("receiverId")
                .decimalType("amount", 50.00)
                .stringType("currency", "USD")
                .stringMatcher("status", enumRegex(TransactionStatus.class), TransactionStatus.PENDING.name())
                .stringType("userMessage", "Test transaction")
                .stringType("timestamp", "2026-05-06T12:00:00.000000Z")
                .object("metadata")
                .closeObject();

        assert payloadBody != null;
        PactDslJsonBody eventBody = new PactDslJsonBody()
                .uuid("eventId")
                .stringMatcher("eventType", enumRegex(TransactionType.class), TransactionType.TRANSFER.name())
                .uuid("aggregatedId")
                .stringType("timestamp", "2026-05-06T12:00:00.000000Z")
                .object("payload", payloadBody);

        return builder
                .usingLegacyMessageDsl() // Tells the builder to default to Messages
                .expectsToReceive("a valid Transaction request event")
                .withContent(eventBody)
                .toPact();
    }

    @Test
    @PactTestFor(pactMethod = "validTransactionEvent")
    void testTransactionEventDeserialization(V4Interaction.AsynchronousMessage message) throws Exception {
        // In Pact V4, we extract the bytes from the nested MessageContents object
        byte[] kafkaMessageBytes = message.getContents().getContents().getValue();

        assert kafkaMessageBytes != null;
        System.out.println("Raw message: " + new String(kafkaMessageBytes));

        TransactionInitiatedEvent event = objectMapper.readValue(kafkaMessageBytes, TransactionInitiatedEvent.class);

        assertThat(event.eventType()).isEqualTo(TransactionType.TRANSFER);
        assertThat(event.referenceId()).isNotNull();
        assertThat(event.payload().amount()).isEqualByComparingTo("50.00");
        assertThat(event.payload().currency()).isEqualByComparingTo(CurrencyType.USD);
        assertThat(event.payload().status()).isEqualTo(TransactionStatus.PENDING);
    }

    private String enumRegex(Class<? extends Enum<?>> enumClass) {
        return Arrays.stream(enumClass.getEnumConstants())
                .map(Enum::name)
                .collect(Collectors.joining("|", "^(", ")$"));
    }
}