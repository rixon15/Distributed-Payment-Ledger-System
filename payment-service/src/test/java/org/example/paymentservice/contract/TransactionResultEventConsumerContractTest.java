package org.example.paymentservice.contract;

import au.com.dius.pact.consumer.dsl.PactBuilder;
import au.com.dius.pact.consumer.dsl.PactDslJsonBody;
import au.com.dius.pact.consumer.junit5.PactConsumerTestExt;
import au.com.dius.pact.consumer.junit5.PactTestFor;
import au.com.dius.pact.consumer.junit5.ProviderType;
import au.com.dius.pact.core.model.V4Interaction;
import au.com.dius.pact.core.model.V4Pact;
import au.com.dius.pact.core.model.annotations.Pact;
import au.com.dius.pact.core.model.annotations.PactDirectory;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(PactConsumerTestExt.class)
@PactTestFor(providerName = "ledger-service", providerType = ProviderType.ASYNCH)
@PactDirectory("src/test/resources/pacts")
class TransactionResultEventConsumerContractTest {

    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());

    @Pact(consumer = "payment-service")
    V4Pact validTransactionResultEvent(PactBuilder builder) {

        PactDslJsonBody eventBody = new PactDslJsonBody()
                .uuid("referenceId")
                .stringMatcher("type", "^(TRANSFER|DEPOSIT|WITHDRAWAL_RESERVE|WITHDRAWAL_SETTLE|WITHDRAWAL_RELEASE)$", "WITHDRAWAL_RESERVE")
                .stringMatcher("status", "^(PENDING|POSTED|REJECTED_NSF|REJECTED_RISK|REJECTED_INACTIVE|REJECTED_VALIDATION|FAILED)$", "POSTED")
                .stringType("reasonCode", "OK")
                .stringType("message", "Transaction posted successfully")
                .stringType("timestamp", "2026-05-06T12:00:00.000000Z");

        return builder
                .usingLegacyMessageDsl()
                .expectsToReceive("a valid transaction result event from ledger")
                .withContent(eventBody)
                .toPact();

    }

    @Test
    @PactTestFor(pactMethod = "validTransactionResultEvent")
    void testTransactionResultEventDeserialization(V4Interaction.AsynchronousMessage message) throws JsonProcessingException {
        byte[] kafkaMessageBytes = message.getContents().getContents().getValue();

        assertThat(kafkaMessageBytes).isNotNull();

        String json = new String(kafkaMessageBytes);

        var node = objectMapper.readTree(json);

        assertThat(node.path("referenceId").asText()).isNotBlank();
        assertThat(UUID.fromString(node.path("referenceId").asText())).isNotNull();
        assertThat(node.path("type").asText()).isEqualTo("WITHDRAWAL_RESERVE");
        assertThat(node.path("status").asText()).isEqualTo("POSTED");
        assertThat(node.path("timestamp").asText()).endsWith("Z");

    }


}
