package com.openfashion.ledgerservice;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openfashion.ledgerservice.dto.ReservationRequest;
import com.openfashion.ledgerservice.model.Account;
import com.openfashion.ledgerservice.model.AccountStatus;
import com.openfashion.ledgerservice.model.AccountType;
import com.openfashion.ledgerservice.model.CurrencyType;
import com.openfashion.ledgerservice.repository.AccountRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@Testcontainers
@AutoConfigureMockMvc
@EmbeddedKafka
@ActiveProfiles("test")
class AccountControllerE2ETest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private MockMvc mockMvc;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    private AccountRepository accountRepository;

    private UUID userId;

    @BeforeEach
    void setup() {
        accountRepository.deleteAll();
        userId = UUID.randomUUID();

        // Setup necessary accounts for the service to function
        createAccount(userId, "Main Wallet", AccountType.ASSET, new BigDecimal("100.00"));
        createAccount(null, "PENDING_WITHDRAWAL", AccountType.LIABILITY, BigDecimal.ZERO);
    }

    @Test
    @DisplayName("POST /accounts/reserve - Success returns 201 Created")
    void testReserveFundsEndpoint() throws Exception {
        ReservationRequest request = new ReservationRequest(
                userId,
                new BigDecimal("50.00"),
                CurrencyType.USD,
                UUID.randomUUID()
        );

        mockMvc.perform(post("/accounts/reserve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated());

    }

    @Test
    @DisplayName("POST /accounts/reserve - Insufficient funds returns 402 Payment Required")
    void testReserveFunds_InsufficientFunds() throws Exception {
        ReservationRequest request = new ReservationRequest(
                userId,
                new BigDecimal("150.00"),
                CurrencyType.USD,
                UUID.randomUUID()
        );

        mockMvc.perform(post("/accounts/reserve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isPaymentRequired());
    }


    private void createAccount(UUID uId, String name, AccountType type, BigDecimal balance) {
        Account a = new Account();
        a.setUserId(uId != null ? uId : UUID.randomUUID());
        a.setName(name);
        a.setType(type);
        a.setBalance(balance);
        a.setCurrency(CurrencyType.USD);
        a.setStatus(AccountStatus.ACTIVE);
        accountRepository.save(a);
    }
}
