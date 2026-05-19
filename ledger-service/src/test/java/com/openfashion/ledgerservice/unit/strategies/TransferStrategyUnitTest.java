package com.openfashion.ledgerservice.unit.strategies;

import com.openfashion.ledgerservice.core.exceptions.AccountInactiveException;
import com.openfashion.ledgerservice.dto.TransactionRequest;
import com.openfashion.ledgerservice.dto.event.TransactionInitiatedEvent;
import com.openfashion.ledgerservice.dto.event.TransactionPayload;
import com.openfashion.ledgerservice.model.*;
import com.openfashion.ledgerservice.repository.AccountRepository;
import com.openfashion.ledgerservice.service.strategy.TransferStrategy;
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
class TransferStrategyUnitTest {

    private AccountRepository accountRepository;
    private TransferStrategy strategy;

    @BeforeEach
    void setUp() {
        accountRepository = mock(AccountRepository.class);
        strategy = new TransferStrategy(accountRepository);
    }

    @Test
    void supports_shouldReturnTrueForTransferAndPaymentOnly() {
        assertThat(strategy.supports(TransactionType.TRANSFER)).isTrue();
        assertThat(strategy.supports(TransactionType.PAYMENT)).isTrue();
        assertThat(strategy.supports(TransactionType.DEPOSIT)).isFalse();
        assertThat(strategy.supports(TransactionType.WITHDRAWAL)).isFalse();
    }

    @Test
    void isValidTransaction_shouldReturnTrueForDistinctUsersAndPositiveAmount() {
        TransactionInitiatedEvent event = event(TransactionType.TRANSFER, UUID.randomUUID(), UUID.randomUUID(), "15.0000");

        assertThat(strategy.isValidTransaction(event)).isTrue();
    }

    @Test
    void isValidTransaction_shouldReturnFalseWhenSenderMissing() {
        TransactionInitiatedEvent event = event(TransactionType.TRANSFER, null, UUID.randomUUID(), "15.0000");

        assertThat(strategy.isValidTransaction(event)).isFalse();
    }

    @Test
    void isValidTransaction_shouldReturnFalseWhenReceiverMissing() {
        TransactionInitiatedEvent event = event(TransactionType.TRANSFER, UUID.randomUUID(), null, "15.0000");

        assertThat(strategy.isValidTransaction(event)).isFalse();
    }

    @Test
    void isValidTransaction_shouldReturnFalseWhenSenderEqualsReceiver() {
        UUID sameUser = UUID.randomUUID();
        TransactionInitiatedEvent event = event(TransactionType.TRANSFER, sameUser, sameUser, "15.0000");

        assertThat(strategy.isValidTransaction(event)).isFalse();
    }

    @Test
    void isValidTransaction_shouldReturnFalseWhenAmountMissingOrNonPositive() {
        UUID senderId = UUID.randomUUID();
        UUID receiverId = UUID.randomUUID();

        TransactionInitiatedEvent missingAmount = new TransactionInitiatedEvent(
                UUID.randomUUID(),
                TransactionType.TRANSFER,
                UUID.randomUUID(),
                Instant.now(),
                new TransactionPayload(
                        senderId,
                        receiverId,
                        null,
                        CurrencyType.USD,
                        TransactionStatus.POSTED,
                        "ok",
                        Instant.now(),
                        Map.of()
                )
        );

        TransactionInitiatedEvent zeroAmount = event(TransactionType.TRANSFER, senderId, receiverId, "0.0000");
        TransactionInitiatedEvent negativeAmount = event(TransactionType.PAYMENT, senderId, receiverId, "-1.0000");

        assertThat(strategy.isValidTransaction(missingAmount)).isFalse();
        assertThat(strategy.isValidTransaction(zeroAmount)).isFalse();
        assertThat(strategy.isValidTransaction(negativeAmount)).isFalse();
    }

    @Test
    void mapToRequest_shouldResolveSenderAsDebitAndReceiverAsCredit() {
        UUID senderId = UUID.randomUUID();
        UUID receiverId = UUID.randomUUID();

        Account senderAccount = userAccount(senderId, AccountStatus.ACTIVE);
        Account receiverAccount = userAccount(receiverId, AccountStatus.ACTIVE);

        when(accountRepository.findByUserIdAndCurrency(senderId, CurrencyType.USD))
                .thenReturn(Optional.of(senderAccount));
        when(accountRepository.findByUserIdAndCurrency(receiverId, CurrencyType.USD))
                .thenReturn(Optional.of(receiverAccount));

        TransactionInitiatedEvent event = event(TransactionType.TRANSFER, senderId, receiverId, "15.0000");

        TransactionRequest request = strategy.mapToRequest(event);

        assertThat(request.getReferenceId()).isEqualTo(event.referenceId());
        assertThat(request.getType()).isEqualTo(TransactionType.TRANSFER);
        assertThat(request.getSenderId()).isEqualTo(senderId);
        assertThat(request.getReceiverId()).isEqualTo(receiverId);
        assertThat(request.getAmount()).isEqualByComparingTo("15.0000");
        assertThat(request.getCurrency()).isEqualTo(CurrencyType.USD);
        assertThat(request.getDebitAccountId()).isEqualTo(senderAccount.getId());
        assertThat(request.getCreditAccountId()).isEqualTo(receiverAccount.getId());
    }

    @Test
    void mapToRequest_shouldPreservePaymentType() {
        UUID senderId = UUID.randomUUID();
        UUID receiverId = UUID.randomUUID();

        Account senderAccount = userAccount(senderId, AccountStatus.ACTIVE);
        Account receiverAccount = userAccount(receiverId, AccountStatus.ACTIVE);

        when(accountRepository.findByUserIdAndCurrency(senderId, CurrencyType.USD))
                .thenReturn(Optional.of(senderAccount));
        when(accountRepository.findByUserIdAndCurrency(receiverId, CurrencyType.USD))
                .thenReturn(Optional.of(receiverAccount));

        TransactionInitiatedEvent event = event(TransactionType.PAYMENT, senderId, receiverId, "5.0000");

        TransactionRequest request = strategy.mapToRequest(event);

        assertThat(request.getType()).isEqualTo(TransactionType.PAYMENT);
    }

    @Test
    void mapToRequest_shouldThrowWhenResolvedUserAccountIsInactive() {
        UUID senderId = UUID.randomUUID();
        UUID receiverId = UUID.randomUUID();

        Account senderAccount = userAccount(senderId, AccountStatus.FROZEN);

        when(accountRepository.findByUserIdAndCurrency(senderId, CurrencyType.USD))
                .thenReturn(Optional.of(senderAccount));

        TransactionInitiatedEvent event = event(TransactionType.TRANSFER, senderId, receiverId, "15.0000");

        assertThatThrownBy(() -> strategy.mapToRequest(event))
                .isInstanceOf(AccountInactiveException.class);
    }

    private TransactionInitiatedEvent event(TransactionType type, UUID senderId, UUID receiverId, String amount) {
        return new TransactionInitiatedEvent(
                UUID.randomUUID(),
                type,
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
}