package org.example.paymentservice.contract;

import au.com.dius.pact.provider.MessageAndMetadata;
import au.com.dius.pact.provider.PactVerifyProvider;
import au.com.dius.pact.provider.junit5.MessageTestTarget;
import au.com.dius.pact.provider.junit5.PactVerificationContext;
import au.com.dius.pact.provider.junit5.PactVerificationInvocationContextProvider;
import au.com.dius.pact.provider.junitsupport.Provider;
import au.com.dius.pact.provider.junitsupport.loader.PactFolder;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.example.paymentservice.dto.event.TransactionInitiatedEvent;
import org.example.paymentservice.dto.event.TransactionPayload;
import org.example.paymentservice.dto.event.TransactionStatus;
import org.example.paymentservice.model.CurrencyType;
import org.example.paymentservice.model.PaymentType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.TestTemplate;
import org.junit.jupiter.api.extension.ExtendWith;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Provider("payment-service")
@PactFolder("../ledger-service/src/test/resources/pacts")
class TransactionEventProviderContractTest {

    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    @SuppressWarnings("JUnitMalformedDeclaration")
    @BeforeEach
    void before(PactVerificationContext context) {
        // Must be set here so Pact knows what to do before the test starts
        context.setTarget(new MessageTestTarget());
    }

    @TestTemplate
    @ExtendWith(PactVerificationInvocationContextProvider.class)
    void verifyPact(PactVerificationContext context) {
        context.verifyInteraction();
    }

    @PactVerifyProvider("a valid Transaction request event")
    MessageAndMetadata transactionEvent() throws JsonProcessingException {

        TransactionPayload payload = new TransactionPayload(
                UUID.randomUUID(),
                UUID.randomUUID(),
                new BigDecimal("50.0"),
                CurrencyType.USD.name(),
                TransactionStatus.PENDING,
                "a valid Transaction request event",
                Instant.now(),
                Map.of()

        );

        TransactionInitiatedEvent transactionInitiatedEvent = new TransactionInitiatedEvent(
                UUID.randomUUID(),
                PaymentType.TRANSFER,
                UUID.randomUUID(),
                Instant.now(),
                payload
        );

        String eventJson = objectMapper.writeValueAsString(transactionInitiatedEvent);

        return new MessageAndMetadata(
                eventJson.getBytes(),
                Map.of("Content-Type", "application/json")
        );
    }

}
