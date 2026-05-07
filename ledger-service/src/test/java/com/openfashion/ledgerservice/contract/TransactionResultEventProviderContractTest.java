package com.openfashion.ledgerservice.contract;

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
import com.openfashion.ledgerservice.dto.event.TransactionResultEvent;
import com.openfashion.ledgerservice.model.TransactionStatus;
import com.openfashion.ledgerservice.model.TransactionType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.TestTemplate;
import org.junit.jupiter.api.extension.ExtendWith;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Provider("ledger-service")
@PactFolder("../payment-service/src/test/resources/pacts")
class TransactionResultEventProviderContractTest {

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

    @PactVerifyProvider("a valid transaction result event from ledger")
    MessageAndMetadata transactionResultEvent() throws JsonProcessingException {

        TransactionResultEvent transactionResultEvent = new TransactionResultEvent(
                UUID.randomUUID(),
                TransactionType.WITHDRAWAL_RESERVE,
                TransactionStatus.POSTED,
                "SUCCESS",
                "Transaction posted successfully",
                Instant.now()
        );

        String eventJson = objectMapper.writeValueAsString(transactionResultEvent);

        return new MessageAndMetadata(
                eventJson.getBytes(),
                Map.of("Content-Type", "application/json")
        );

    }
}
