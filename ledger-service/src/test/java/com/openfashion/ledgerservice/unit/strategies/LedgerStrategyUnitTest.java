package com.openfashion.ledgerservice.unit.strategies;

import com.openfashion.ledgerservice.core.exceptions.AccountInactiveException;
import com.openfashion.ledgerservice.core.exceptions.AccountNotFoundException;
import com.openfashion.ledgerservice.core.exceptions.MissingSystemAccountException;
import com.openfashion.ledgerservice.dto.TransactionRequest;
import com.openfashion.ledgerservice.dto.event.TransactionInitiatedEvent;
import com.openfashion.ledgerservice.dto.event.TransactionPayload;
import com.openfashion.ledgerservice.model.*;
import com.openfashion.ledgerservice.repository.AccountRepository;
import com.openfashion.ledgerservice.service.strategy.LedgerStrategy;
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
class LedgerStrategyUnitTest {

    private AccountRepository accountRepository;
    private TestLedgerStrategy strategy;

    @BeforeEach
    void setUp() {
        accountRepository = mock(AccountRepository.class);
        strategy = new TestLedgerStrategy(accountRepository);
    }

    @Test
    void resolveUserAccount_shouldReturnAccountIdWhenActive() {
        UUID userId = UUID.randomUUID();
        Account account = userAccount(userId, CurrencyType.USD, AccountStatus.ACTIVE);

        when(accountRepository.findByUserIdAndCurrency(userId, CurrencyType.USD))
                .thenReturn(Optional.of(account));

        UUID resolved = strategy.callResolveUserAccount(userId, CurrencyType.USD);

        assertThat(resolved).isEqualTo(account.getId());
    }

    @Test
    void resolveUserAccount_shouldThrowWhenUserAccountMissing() {
        UUID userId = UUID.randomUUID();

        when(accountRepository.findByUserIdAndCurrency(userId, CurrencyType.USD))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> strategy.callResolveUserAccount(userId, CurrencyType.USD))
                .isInstanceOf(AccountNotFoundException.class);
    }

    @Test
    void resolveUserAccount_shouldThrowWhenAccountInactive() {
        UUID userId = UUID.randomUUID();
        Account account = userAccount(userId, CurrencyType.USD, AccountStatus.FROZEN);

        when(accountRepository.findByUserIdAndCurrency(userId, CurrencyType.USD))
                .thenReturn(Optional.of(account));

        assertThatThrownBy(() -> strategy.callResolveUserAccount(userId, CurrencyType.USD))
                .isInstanceOf(AccountInactiveException.class);
    }

    @Test
    void resolveSystemAccount_shouldReturnAccountIdWhenFound() {
        Account account = systemAccount("WORLD_LIQUIDITY", CurrencyType.USD);

        when(accountRepository.findByNameAndCurrency("WORLD_LIQUIDITY", CurrencyType.USD))
                .thenReturn(Optional.of(account));

        UUID resolved = strategy.callResolveSystemAccount("WORLD_LIQUIDITY", CurrencyType.USD);

        assertThat(resolved).isEqualTo(account.getId());
    }

    @Test
    void resolveSystemAccount_shouldThrowWhenMissing() {
        when(accountRepository.findByNameAndCurrency("WORLD_LIQUIDITY", CurrencyType.USD))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> strategy.callResolveSystemAccount("WORLD_LIQUIDITY", CurrencyType.USD))
                .isInstanceOf(MissingSystemAccountException.class);
    }

    @Test
    void createRejectedRequest_shouldMapCommonRejectedFields() {
        UUID referenceId = UUID.randomUUID();
        UUID senderId = UUID.randomUUID();
        UUID receiverId = UUID.randomUUID();

        TransactionInitiatedEvent event = event(
                referenceId,
                TransactionType.TRANSFER,
                senderId,
                receiverId,
                "10.123456",
                CurrencyType.USD,
                TransactionStatus.REJECTED_NSF
        );

        TransactionRequest request = strategy.createRejectedRequest(event);

        assertThat(request.getReferenceId()).isEqualTo(referenceId);
        assertThat(request.getType()).isEqualTo(TransactionType.TRANSFER);
        assertThat(request.getSenderId()).isEqualTo(senderId);
        assertThat(request.getReceiverId()).isEqualTo(receiverId);
        assertThat(request.getCurrency()).isEqualTo(CurrencyType.USD);
        assertThat(request.getAmount()).isEqualByComparingTo("10.1235");
    }

    @Test
    void resolveTransactionType_shouldDefaultToEventType() {
        TransactionInitiatedEvent event = event(
                UUID.randomUUID(),
                TransactionType.PAYMENT,
                UUID.randomUUID(),
                UUID.randomUUID(),
                "5.0000",
                CurrencyType.USD,
                TransactionStatus.POSTED
        );

        TransactionType resolved = strategy.callResolveTransactionType(event);

        assertThat(resolved).isEqualTo(TransactionType.PAYMENT);
    }

    private TransactionInitiatedEvent event(
            UUID referenceId,
            TransactionType type,
            UUID senderId,
            UUID receiverId,
            String amount,
            CurrencyType currency,
            TransactionStatus status
    ) {
        return new TransactionInitiatedEvent(
                UUID.randomUUID(),
                type,
                referenceId,
                Instant.now(),
                new TransactionPayload(
                        senderId,
                        receiverId,
                        new BigDecimal(amount),
                        currency,
                        status,
                        "message",
                        Instant.now(),
                        Map.of("k", "v")
                )
        );
    }

    private Account userAccount(UUID userId, CurrencyType currency, AccountStatus status) {
        Account account = new Account();
        account.setId(UUID.randomUUID());
        account.setUserId(userId);
        account.setName("USER");
        account.setType(AccountType.ASSET);
        account.setCurrency(currency);
        account.setBalance(new BigDecimal("100.0000"));
        account.setStatus(status);
        return account;
    }

    private Account systemAccount(String name, CurrencyType currency) {
        Account account = new Account();
        account.setId(UUID.randomUUID());
        account.setUserId(UUID.randomUUID());
        account.setName(name);
        account.setType(AccountType.ASSET);
        account.setCurrency(currency);
        account.setBalance(new BigDecimal("1000.0000"));
        account.setStatus(AccountStatus.ACTIVE);
        return account;
    }

    private static class TestLedgerStrategy extends LedgerStrategy {
        TestLedgerStrategy(AccountRepository accountRepository) {
            super(accountRepository);
        }

        @Override
        public boolean supports(TransactionType transactionType) {
            return false;
        }

        @Override
        public boolean isValidTransaction(TransactionInitiatedEvent transactionInitiatedEvent) {
            return false;
        }

        @Override
        public TransactionRequest mapToRequest(TransactionInitiatedEvent event) {
            return null;
        }

        UUID callResolveUserAccount(UUID userId, CurrencyType currencyType) {
            return resolveUserAccount(userId, currencyType);
        }

        UUID callResolveSystemAccount(String systemAccountName, CurrencyType currencyType) {
            return resolveSystemAccount(systemAccountName, currencyType);
        }

        TransactionType callResolveTransactionType(TransactionInitiatedEvent event) {
            return resolveTransactonType(event);
        }
    }
}