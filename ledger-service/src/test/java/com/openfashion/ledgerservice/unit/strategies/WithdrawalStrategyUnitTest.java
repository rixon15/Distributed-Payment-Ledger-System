package com.openfashion.ledgerservice.unit.strategies;

import com.openfashion.ledgerservice.dto.TransactionRequest;
import com.openfashion.ledgerservice.dto.event.TransactionInitiatedEvent;
import com.openfashion.ledgerservice.dto.event.TransactionPayload;
import com.openfashion.ledgerservice.model.*;
import com.openfashion.ledgerservice.repository.AccountRepository;
import com.openfashion.ledgerservice.service.strategy.WithdrawalStrategy;
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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WithdrawalStrategyUnitTest {

    private AccountRepository accountRepository;
    private WithdrawalStrategy strategy;

    @BeforeEach
    void setUp() {
        accountRepository = mock(AccountRepository.class);
        strategy = new WithdrawalStrategy(accountRepository);
    }

    @Test
    void supports_shouldReturnTrueOnlyForWithdrawal() {
        assertThat(strategy.supports(TransactionType.WITHDRAWAL)).isTrue();
        assertThat(strategy.supports(TransactionType.TRANSFER)).isFalse();
        assertThat(strategy.supports(TransactionType.DEPOSIT)).isFalse();
        assertThat(strategy.supports(TransactionType.PAYMENT)).isFalse();
    }

    @Test
    void isValidTransaction_shouldReturnTrueForSelfWithdrawalWithPositiveAmount() {
        UUID userId = UUID.randomUUID();

        TransactionInitiatedEvent event = event(userId, userId, "20.0000", TransactionStatus.PENDING);

        assertThat(strategy.isValidTransaction(event)).isTrue();
    }

    @Test
    void isValidTransaction_shouldReturnFalseForMissingUsersOrBadAmount() {
        UUID userId = UUID.randomUUID();

        assertThat(strategy.isValidTransaction(event(null, userId, "20.0000", TransactionStatus.PENDING))).isFalse();
        assertThat(strategy.isValidTransaction(event(userId, null, "20.0000", TransactionStatus.PENDING))).isFalse();
        assertThat(strategy.isValidTransaction(event(userId, UUID.randomUUID(), "20.0000", TransactionStatus.PENDING))).isFalse();
        assertThat(strategy.isValidTransaction(event(userId, userId, "0.0000", TransactionStatus.PENDING))).isFalse();
        assertThat(strategy.isValidTransaction(event(userId, userId, "-1.0000", TransactionStatus.PENDING))).isFalse();
    }

    @Test
    void mapToRequest_shouldMapPendingToWithdrawalReserve() {
        UUID userId = UUID.randomUUID();

        Account userAccount = userAccount(userId, "USER");
        Account pendingWithdrawal = systemAccount("PENDING_WITHDRAWAL");

        when(accountRepository.findByUserIdAndCurrency(userId, CurrencyType.USD))
                .thenReturn(Optional.of(userAccount));
        when(accountRepository.findByNameAndCurrency("PENDING_WITHDRAWAL", CurrencyType.USD))
                .thenReturn(Optional.of(pendingWithdrawal));

        TransactionInitiatedEvent event = event(userId, userId, "20.0000", TransactionStatus.PENDING);

        TransactionRequest request = strategy.mapToRequest(event);

        assertThat(request.getReferenceId()).isEqualTo(event.referenceId());
        assertThat(request.getSenderId()).isEqualTo(userId);
        assertThat(request.getReceiverId()).isEqualTo(userId);
        assertThat(request.getCurrency()).isEqualTo(CurrencyType.USD);
        assertThat(request.getAmount()).isEqualByComparingTo("20.0000");
        assertThat(request.getType()).isEqualTo(TransactionType.WITHDRAWAL_RESERVE);
        assertThat(request.getDebitAccountId()).isEqualTo(userAccount.getId());
        assertThat(request.getCreditAccountId()).isEqualTo(pendingWithdrawal.getId());
    }

    @Test
    void mapToRequest_shouldMapPostedToWithdrawalSettle() {
        UUID userId = UUID.randomUUID();

        Account pendingWithdrawal = systemAccount("PENDING_WITHDRAWAL");
        Account worldLiquidity = systemAccount("WORLD_LIQUIDITY");

        when(accountRepository.findByNameAndCurrency("PENDING_WITHDRAWAL", CurrencyType.USD))
                .thenReturn(Optional.of(pendingWithdrawal));
        when(accountRepository.findByNameAndCurrency("WORLD_LIQUIDITY", CurrencyType.USD))
                .thenReturn(Optional.of(worldLiquidity));

        TransactionInitiatedEvent event = event(userId, userId, "20.0000", TransactionStatus.POSTED);

        TransactionRequest request = strategy.mapToRequest(event);

        assertThat(request.getReferenceId()).isEqualTo(event.referenceId());
        assertThat(request.getType()).isEqualTo(TransactionType.WITHDRAWAL_SETTLE);
        assertThat(request.getDebitAccountId()).isEqualTo(pendingWithdrawal.getId());
        assertThat(request.getCreditAccountId()).isEqualTo(worldLiquidity.getId());
    }

    @Test
    void mapToRequest_shouldMapFailedToWithdrawalRelease() {
        UUID userId = UUID.randomUUID();

        Account pendingWithdrawal = systemAccount("PENDING_WITHDRAWAL");
        Account userAccount = userAccount(userId, "USER");

        when(accountRepository.findByNameAndCurrency("PENDING_WITHDRAWAL", CurrencyType.USD))
                .thenReturn(Optional.of(pendingWithdrawal));
        when(accountRepository.findByUserIdAndCurrency(userId, CurrencyType.USD))
                .thenReturn(Optional.of(userAccount));

        TransactionInitiatedEvent event = event(userId, userId, "20.0000", TransactionStatus.FAILED);

        TransactionRequest request = strategy.mapToRequest(event);

        assertThat(request.getReferenceId()).isEqualTo(event.referenceId());
        assertThat(request.getType()).isEqualTo(TransactionType.WITHDRAWAL_RELEASE);
        assertThat(request.getDebitAccountId()).isEqualTo(pendingWithdrawal.getId());
        assertThat(request.getCreditAccountId()).isEqualTo(userAccount.getId());
    }

    @Test
    void mapToRequest_shouldThrowForRejectedStatusesHandledByRejectedFlow() {
        UUID userId = UUID.randomUUID();

        TransactionInitiatedEvent rejectedNsf = event(userId, userId, "20.0000", TransactionStatus.REJECTED_NSF);
        TransactionInitiatedEvent rejectedValidation = event(userId, userId, "20.0000", TransactionStatus.REJECTED_VALIDATION);
        TransactionInitiatedEvent rejectedRisk = event(userId, userId, "20.0000", TransactionStatus.REJECTED_RISK);
        TransactionInitiatedEvent rejectedInactive = event(userId, userId, "20.0000", TransactionStatus.REJECTED_INACTIVE);

        assertThatThrownBy(() -> strategy.mapToRequest(rejectedNsf))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown withdrawal status");

        assertThatThrownBy(() -> strategy.mapToRequest(rejectedValidation))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown withdrawal status");

        assertThatThrownBy(() -> strategy.mapToRequest(rejectedRisk))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown withdrawal status");

        assertThatThrownBy(() -> strategy.mapToRequest(rejectedInactive))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown withdrawal status");
    }

    @Test
    void createRejectedRequest_shouldMapRejectedStatusesToWithdrawalReleaseCompensation() {
        UUID userId = UUID.randomUUID();

        TransactionRequest nsf = strategy.createRejectedRequest(event(userId, userId, "20.0000", TransactionStatus.REJECTED_NSF));
        TransactionRequest validation = strategy.createRejectedRequest(event(userId, userId, "20.0000", TransactionStatus.REJECTED_VALIDATION));
        TransactionRequest inactive = strategy.createRejectedRequest(event(userId, userId, "20.0000", TransactionStatus.REJECTED_INACTIVE));
        TransactionRequest risk = strategy.createRejectedRequest(event(userId, userId, "20.0000", TransactionStatus.REJECTED_RISK));
        TransactionRequest failed = strategy.createRejectedRequest(event(userId, userId, "20.0000", TransactionStatus.FAILED));

        assertThat(nsf.getType()).isEqualTo(TransactionType.WITHDRAWAL_RELEASE);
        assertThat(validation.getType()).isEqualTo(TransactionType.WITHDRAWAL_RELEASE);
        assertThat(inactive.getType()).isEqualTo(TransactionType.WITHDRAWAL_RELEASE);
        assertThat(risk.getType()).isEqualTo(TransactionType.WITHDRAWAL_RELEASE);
        assertThat(failed.getType()).isEqualTo(TransactionType.WITHDRAWAL_RELEASE);
    }

    @Test
    void createRejectedRequest_shouldMapPendingAndPostedToLifecycleSpecificTypes() {
        UUID userId = UUID.randomUUID();

        TransactionRequest pending = strategy.createRejectedRequest(event(userId, userId, "20.0000", TransactionStatus.PENDING));
        TransactionRequest posted = strategy.createRejectedRequest(event(userId, userId, "20.0000", TransactionStatus.POSTED));

        assertThat(pending.getType()).isEqualTo(TransactionType.WITHDRAWAL_RESERVE);
        assertThat(posted.getType()).isEqualTo(TransactionType.WITHDRAWAL_SETTLE);
    }

    @Test
    void createRejectedRequest_shouldPreserveCommonFields() {
        UUID senderId = UUID.randomUUID();
        UUID referenceId = UUID.randomUUID();

        TransactionInitiatedEvent event = new TransactionInitiatedEvent(
                UUID.randomUUID(),
                TransactionType.WITHDRAWAL,
                referenceId,
                Instant.now(),
                new TransactionPayload(
                        senderId,
                        senderId,
                        new BigDecimal("20.123456"),
                        CurrencyType.USD,
                        TransactionStatus.REJECTED_NSF,
                        "withdrawal failed",
                        Instant.now(),
                        Map.of("reason", "bank-error")
                )
        );

        TransactionRequest request = strategy.createRejectedRequest(event);

        assertThat(request.getReferenceId()).isEqualTo(referenceId);
        assertThat(request.getSenderId()).isEqualTo(senderId);
        assertThat(request.getReceiverId()).isEqualTo(senderId);
        assertThat(request.getCurrency()).isEqualTo(CurrencyType.USD);
        assertThat(request.getAmount()).isEqualByComparingTo("20.1235");
        assertThat(request.getType()).isEqualTo(TransactionType.WITHDRAWAL_RELEASE);
    }

    @Test
    void createRejectedRequest_shouldThrowWhenStatusCannotBeResolved() {
        UUID userId = UUID.randomUUID();

        TransactionInitiatedEvent event = event(userId, userId, "20.0000", TransactionStatus.VOID);

        assertThatThrownBy(() -> strategy.createRejectedRequest(event))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown status for withdrawal");
    }

    private TransactionInitiatedEvent event(UUID senderId, UUID receiverId, String amount, TransactionStatus status) {
        return new TransactionInitiatedEvent(
                UUID.randomUUID(),
                TransactionType.WITHDRAWAL,
                UUID.randomUUID(),
                Instant.now(),
                new TransactionPayload(
                        senderId,
                        receiverId,
                        new BigDecimal(amount),
                        CurrencyType.USD,
                        status,
                        "withdrawal",
                        Instant.now(),
                        Map.of()
                )
        );
    }

    private Account userAccount(UUID userId, String name) {
        Account account = new Account();
        account.setId(UUID.randomUUID());
        account.setUserId(userId);
        account.setName(name);
        account.setType(AccountType.ASSET);
        account.setCurrency(CurrencyType.USD);
        account.setBalance(new BigDecimal("100.0000"));
        account.setStatus(AccountStatus.ACTIVE);
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