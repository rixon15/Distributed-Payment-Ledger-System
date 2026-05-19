package com.openfashion.ledgerservice.unit.strategies;

import com.openfashion.ledgerservice.core.exceptions.AccountNotFoundException;
import com.openfashion.ledgerservice.core.exceptions.MissingSystemAccountException;
import com.openfashion.ledgerservice.dto.TransactionRequest;
import com.openfashion.ledgerservice.dto.event.TransactionInitiatedEvent;
import com.openfashion.ledgerservice.dto.event.TransactionPayload;
import com.openfashion.ledgerservice.model.*;
import com.openfashion.ledgerservice.repository.AccountRepository;
import com.openfashion.ledgerservice.service.strategy.DepositStrategy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DepositStrategyUnitTest {

    private AccountRepository accountRepository;
    private DepositStrategy strategy;

    @BeforeEach
    void setUp() {
        accountRepository = mock(AccountRepository.class);
        strategy = new DepositStrategy(accountRepository);
    }

    @Test
    void supports_shouldReturnTrueOnlyForDeposit() {
        assertThat(strategy.supports(TransactionType.DEPOSIT)).isTrue();
        assertThat(strategy.supports(TransactionType.TRANSFER)).isFalse();
        assertThat(strategy.supports(TransactionType.WITHDRAWAL)).isFalse();
    }

    @Test
    void isValidTransaction_shouldReturnTrueForSelfDepositWithPositiveAmount() {
        UUID userId = UUID.randomUUID();
        TransactionInitiatedEvent event = event(userId, userId, "10.0000");

        assertThat(strategy.isValidTransaction(event)).isTrue();
    }

    @Test
    void isValidTransaction_shouldReturnFalseWhenSenderMissing() {
        UUID receiverId = UUID.randomUUID();
        TransactionInitiatedEvent event = event(null, receiverId, "10.0000");

        assertThat(strategy.isValidTransaction(event)).isFalse();
    }

    @Test
    void isValidTransaction_shouldReturnFalseWhenReceiverMissing() {
        UUID senderId = UUID.randomUUID();
        TransactionInitiatedEvent event = event(senderId, null, "10.0000");

        assertThat(strategy.isValidTransaction(event)).isFalse();
    }

    @Test
    void isValidTransaction_shouldReturnFalseWhenSenderAndReceiverDiffer() {
        TransactionInitiatedEvent event = event(UUID.randomUUID(), UUID.randomUUID(), "10.0000");

        assertThat(strategy.isValidTransaction(event)).isFalse();
    }

    @Test
    void isValidTransaction_shouldReturnFalseWhenAmountMissing() {
        UUID userId = UUID.randomUUID();

        TransactionInitiatedEvent event = new TransactionInitiatedEvent(
                UUID.randomUUID(),
                TransactionType.DEPOSIT,
                UUID.randomUUID(),
                Instant.now(),
                new TransactionPayload(
                        userId,
                        userId,
                        null,
                        CurrencyType.USD,
                        TransactionStatus.POSTED,
                        "ok",
                        Instant.now(),
                        Map.of()
                )
        );

        assertThat(strategy.isValidTransaction(event)).isFalse();
    }

    @Test
    void isValidTransaction_shouldReturnFalseWhenAmountNotPositive() {
        UUID userId = UUID.randomUUID();
        TransactionInitiatedEvent zero = event(userId, userId, "0.0000");
        TransactionInitiatedEvent negative = event(userId, userId, "-1.0000");

        assertThat(strategy.isValidTransaction(zero)).isFalse();
        assertThat(strategy.isValidTransaction(negative)).isFalse();
    }

    @Test
    void mapToRequest_shouldResolveWorldLiquidityAsDebitAndReceiverAsCredit() {
        UUID userId = UUID.randomUUID();

        Account worldLiquidity = systemAccount("WORLD_LIQUIDITY");
        Account receiverAccount = userAccount(userId, AccountStatus.ACTIVE);

        when(accountRepository.findByNameAndCurrency("WORLD_LIQUIDITY", CurrencyType.USD))
                .thenReturn(Optional.of(worldLiquidity));
        when(accountRepository.findByUserIdAndCurrency(userId, CurrencyType.USD))
                .thenReturn(Optional.of(receiverAccount));

        TransactionInitiatedEvent event = event(userId, userId, "10.123456");

        TransactionRequest request = strategy.mapToRequest(event);

        assertThat(request.getReferenceId()).isEqualTo(event.referenceId());
        assertThat(request.getType()).isEqualTo(TransactionType.DEPOSIT);
        assertThat(request.getSenderId()).isEqualTo(userId);
        assertThat(request.getReceiverId()).isEqualTo(userId);
        assertThat(request.getCurrency()).isEqualTo(CurrencyType.USD);
        assertThat(request.getAmount()).isEqualByComparingTo("10.1235");
        assertThat(request.getDebitAccountId()).isEqualTo(worldLiquidity.getId());
        assertThat(request.getCreditAccountId()).isEqualTo(receiverAccount.getId());
    }

    @Test
    void mapToRequest_shouldThrowWhenSystemAccountMissing() {
        UUID userId = UUID.randomUUID();

        when(accountRepository.findByNameAndCurrency("WORLD_LIQUIDITY", CurrencyType.USD))
                .thenReturn(Optional.empty());

        TransactionInitiatedEvent event = event(userId, userId, "10.0000");

        assertThatThrownBy(() -> strategy.mapToRequest(event))
                .isInstanceOf(MissingSystemAccountException.class);
    }

    @Test
    void mapToRequest_shouldThrowWhenReceiverAccountMissing() {
        UUID userId = UUID.randomUUID();
        Account worldLiquidity = systemAccount("WORLD_LIQUIDITY");

        when(accountRepository.findByNameAndCurrency("WORLD_LIQUIDITY", CurrencyType.USD))
                .thenReturn(Optional.of(worldLiquidity));
        when(accountRepository.findByUserIdAndCurrency(userId, CurrencyType.USD))
                .thenReturn(Optional.empty());

        TransactionInitiatedEvent event = event(userId, userId, "10.0000");

        assertThatThrownBy(() -> strategy.mapToRequest(event))
                .isInstanceOf(AccountNotFoundException.class);
    }

    private TransactionInitiatedEvent event(UUID senderId, UUID receiverId, String amount) {
        return new TransactionInitiatedEvent(
                UUID.randomUUID(),
                TransactionType.DEPOSIT,
                UUID.randomUUID(),
                Instant.now(),
                new TransactionPayload(
                        senderId,
                        receiverId,
                        new BigDecimal(amount),
                        CurrencyType.USD,
                        TransactionStatus.POSTED,
                        "ok",
                        Instant.now(),
                        Map.of()
                )
        );
    }

    private Account userAccount(UUID userId, AccountStatus status) {
        Account account = new Account();
        account.setId(UUID.randomUUID());
        account.setUserId(userId);
        account.setName("USER");
        account.setType(AccountType.ASSET);
        account.setCurrency(CurrencyType.USD);
        account.setBalance(new BigDecimal("100.0000"));
        account.setStatus(status);
        return account;
    }

    private Account systemAccount(String name) {
        Account account = new Account();
        account.setId(UUID.randomUUID());
        account.setUserId(UUID.randomUUID());
        account.setName(name);
        account.setType(AccountType.ASSET);
        account.setCurrency(CurrencyType.USD);
        account.setBalance(new BigDecimal("1000.0000"));
        account.setStatus(AccountStatus.ACTIVE);
        return account;
    }
}