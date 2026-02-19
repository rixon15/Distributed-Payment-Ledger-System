package com.openfashion.ledgerservice;

import com.openfashion.ledgerservice.core.exceptions.*;
import com.openfashion.ledgerservice.core.util.MoneyUtil;
import com.openfashion.ledgerservice.dto.ReleaseRequest;
import com.openfashion.ledgerservice.dto.ReservationRequest;
import com.openfashion.ledgerservice.dto.TransactionRequest;
import com.openfashion.ledgerservice.dto.event.WithdrawalConfirmedEvent;
import com.openfashion.ledgerservice.dto.event.WithdrawalPayload;
import com.openfashion.ledgerservice.model.*;
import com.openfashion.ledgerservice.repository.AccountRepository;
import com.openfashion.ledgerservice.repository.OutboxRepository;
import com.openfashion.ledgerservice.repository.PostingRepository;
import com.openfashion.ledgerservice.repository.TransactionRepository;
import com.openfashion.ledgerservice.service.imp.LedgerServiceImp;
import com.openfashion.ledgerservice.service.strategy.AccountPair;
import com.openfashion.ledgerservice.service.strategy.AccountResolutionStrategy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class LedgerServiceUnitTest {

    @Mock
    private AccountRepository accountRepository;
    @Mock
    private TransactionRepository transactionRepository;
    @Mock
    private PostingRepository postingRepository;
    @Mock
    private OutboxRepository outboxRepository;

    private LedgerServiceImp ledgerService;

    private UUID senderId;
    private UUID receiverId;
    private UUID referenceId;
    private Account senderAccount;
    private Account receiverAccount;
    private Account worldAccount;
    private Account withdrawalAccount;
    private Transaction pendingTransaction;
    private Posting originalDebit;
    private Posting pendingCredit;

    @Mock private AccountResolutionStrategy transferStrategy;
    @Mock private AccountResolutionStrategy depositStrategy;
    @Mock private AccountResolutionStrategy feeStrategy;
    @Mock private AccountResolutionStrategy withdrawalStrategy;

    @BeforeEach
    void setUp() {
        senderId = UUID.randomUUID();
        receiverId = UUID.randomUUID();
        referenceId = UUID.randomUUID();

        senderAccount = createMockAccount(senderId, "Sender", AccountType.ASSET, new BigDecimal("100.00"));
        receiverAccount = createMockAccount(receiverId, "Receiver", AccountType.ASSET, new BigDecimal("50.00"));
        worldAccount = createMockAccount(null, "WORLD_LIQUIDITY", AccountType.EQUITY, new BigDecimal("1000.00"));
        withdrawalAccount = createMockAccount(UUID.randomUUID(), "PENDING_WITHDRAWAL", AccountType.LIABILITY, BigDecimal.ZERO);

        pendingTransaction = new Transaction();
        pendingTransaction.setId(UUID.randomUUID());
        pendingTransaction.setReferenceId(referenceId.toString());
        pendingTransaction.setStatus(TransactionStatus.PENDING);

        // This simulates the original "Reservation" posting we need to look up to find the amount
        originalDebit = Posting.builder()
                .account(senderAccount)
                .amount(new BigDecimal("30.00"))
                .direction(PostingDirection.DEBIT)
                .transaction(pendingTransaction)
                .build();

        pendingCredit = Posting.builder()
                .account(withdrawalAccount)
                .amount(new BigDecimal("30.0000"))
                .direction(PostingDirection.CREDIT)
                .transaction(pendingTransaction)
                .build();
        lenient().when(transferStrategy.supports(TransactionType.TRANSFER)).thenReturn(true);
        lenient().when(transferStrategy.supports(TransactionType.PAYMENT)).thenReturn(true);
        lenient().when(transferStrategy.supports(TransactionType.REFUND)).thenReturn(true);
        lenient().when(transferStrategy.supports(TransactionType.ADJUSTMENT)).thenReturn(true);

        lenient().when(depositStrategy.supports(TransactionType.DEPOSIT)).thenReturn(true);
        lenient().when(withdrawalStrategy.supports(TransactionType.WITHDRAWAL)).thenReturn(true);

        lenient().when(feeStrategy.supports(TransactionType.FEE)).thenReturn(true);
        lenient().when(feeStrategy.supports(TransactionType.INTEREST)).thenReturn(true);

        // 3. Put them all in the list
        List<AccountResolutionStrategy> strategies = List.of(
                transferStrategy, depositStrategy, withdrawalStrategy, feeStrategy
        );

        ledgerService = new LedgerServiceImp(
                accountRepository,
                transactionRepository,
                postingRepository,
                outboxRepository,
                strategies
                // ... other dependencies
        );

        ledgerService.initStrategy();

        // 4. Clear invocations
        Mockito.clearInvocations(transferStrategy, depositStrategy, withdrawalStrategy, feeStrategy);
        }

    @ParameterizedTest
    @EnumSource(value = TransactionType.class, names = {"TRANSFER", "PAYMENT", "DEPOSIT", "WITHDRAWAL"})
    @DisplayName("Should process successful transactions for valid types")
    void testSuccessfulTransactions(TransactionType type) {
        TransactionRequest request = createRequest(type, new BigDecimal("20.0"));

        stubStrategyResolution(type);
        when(transactionRepository.existsByReferenceId(any())).thenReturn(false);

        ledgerService.processTransaction(request);

        verify(transactionRepository).save(argThat(t -> t.getStatus() == TransactionStatus.POSTED));
        verify(postingRepository).saveAll(anyList());
        verify(outboxRepository).save(any());

        if (type == TransactionType.TRANSFER) {
            assertThat(senderAccount.getBalance()).isEqualByComparingTo("80.00");
            assertThat(receiverAccount.getBalance()).isEqualByComparingTo("70.00");
        }
    }

    @ParameterizedTest
    @EnumSource(value = TransactionType.class, names = {"FEE", "INTEREST", "REFUND", "ADJUSTMENT"})
    @DisplayName("Should process specialized transaction types (Fees, Interest, etc)")
    void testSpecializedTransactions(TransactionType type) {
        TransactionRequest request = createRequest(type, new BigDecimal("10"));

        stubStrategyResolution(type);

        when(transactionRepository.existsByReferenceId(any())).thenReturn(false);
        ledgerService.processTransaction(request);

        verify(transactionRepository).save(argThat(t -> t.getStatus() == TransactionStatus.POSTED));
    }

    @Test
    @DisplayName("Should return immediately if referenceId already exists")
    void testIdempotency() {
        TransactionRequest request = createRequest(TransactionType.TRANSFER, new BigDecimal("100.00"));

        when(transactionRepository.existsByReferenceId(any())).thenReturn(true);

        ledgerService.processTransaction(request);

        verify(accountRepository, never()).save(any());
        verify(postingRepository, never()).saveAll(any());
    }

    @Test
    @DisplayName("Should reject with NSF when sender balance is too low")
    void testNSF_Rejection() {
        TransactionRequest request = createRequest(TransactionType.TRANSFER, new BigDecimal("150.00"));

        stubStrategyResolution(TransactionType.TRANSFER);

        ledgerService.processTransaction(request);

        verify(transactionRepository).save(argThat(t -> t.getStatus() == TransactionStatus.REJECTED_NSF));
        assertThat(senderAccount.getBalance()).isEqualByComparingTo("100.00");
    }

    @Test
    @DisplayName("Should reject if sender account is frozen")
    void testFrozenAccount_Rejection() {
        senderAccount.setStatus(AccountStatus.FROZEN);
        TransactionRequest request = createRequest(TransactionType.TRANSFER, new BigDecimal("100.00"));

        stubStrategyResolution(TransactionType.TRANSFER);
        when(transactionRepository.existsByReferenceId(any())).thenReturn(false);

        ledgerService.processTransaction(request);

        verify(transactionRepository).save(argThat(t -> t.getStatus() == TransactionStatus.REJECTED_INACTIVE));
    }

    @Test
    @DisplayName("Should throw AccountNotFoundException if user doesn't exist")
    void testAccountNotFound() {
        TransactionRequest request = createRequest(TransactionType.TRANSFER, new BigDecimal("100.00"));

        when(transferStrategy.resolve(any(TransactionRequest.class), any(AccountRepository.class)))
                .thenThrow(new AccountNotFoundException(request.getSenderId()));

        assertThatThrownBy(() -> ledgerService.processTransaction(request))
                .isInstanceOf(AccountNotFoundException.class);
    }

    @Test
    @DisplayName("Should throw MissingWorldAccountException if WORLD_LIQUIDITY is missing")
    void testWorldAccountMissing() {
        TransactionRequest request = createRequest(TransactionType.DEPOSIT, new BigDecimal("100.00"));

        when(depositStrategy.resolve(any(), any()))
                .thenThrow(new MissingSystemAccountException("WORLD_LIQUIDITY"));

        assertThatThrownBy(() -> ledgerService.processTransaction(request))
                .isInstanceOf(MissingSystemAccountException.class);
    }

    @Test
    @DisplayName("Should strictly enforce 4 decimal places via MoneyUtil")
    void testPrecisionHandling() {
        BigDecimal amount = new BigDecimal("10.1234999");
        TransactionRequest request = createRequest(TransactionType.TRANSFER, MoneyUtil.format(amount));

        stubStrategyResolution(TransactionType.TRANSFER);

        ledgerService.processTransaction(request);

        verify(postingRepository).saveAll(argThat(postings ->
                StreamSupport.stream(postings.spliterator(), false)
                        .allMatch(p -> p.getAmount().scale() == 4)));
    }

    @Test
    @DisplayName("Should save correct Outbox Event metadata on success")
    void testOutboxContent() {
        TransactionRequest request = createRequest(TransactionType.TRANSFER, new BigDecimal("10.00"));
        stubStrategyResolution(TransactionType.TRANSFER);

        ledgerService.processTransaction(request);

        ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outboxRepository).save(captor.capture());

        OutboxEvent savedEvent = captor.getValue();
        assertThat(savedEvent.getEventType()).isEqualTo("TRANSACTION_COMPLETED");
        assertThat(savedEvent.getAggregateId()).isEqualTo(request.getReferenceId());
        assertThat(savedEvent.getPayload()).contains(request.getReferenceId());
    }

    @Test
    @DisplayName("Should successfully reserve funds and update balances")
    void testReserveFunds_Success() {
        ReservationRequest request = new ReservationRequest(senderId, new BigDecimal("30.00"), CurrencyType.USD, referenceId);

        when(transactionRepository.existsByReferenceId(referenceId.toString())).thenReturn(false);
        when(accountRepository.findByUserIdAndCurrency(senderId, CurrencyType.USD)).thenReturn(Optional.of(senderAccount));
        when(accountRepository.findByNameAndCurrency("PENDING_WITHDRAWAL", CurrencyType.USD)).thenReturn(Optional.of(withdrawalAccount));

        ledgerService.reserveFunds(request);

        assertThat(senderAccount.getBalance()).isEqualByComparingTo("70.00");
        assertThat(withdrawalAccount.getBalance()).isEqualByComparingTo("30.00");

        verify(transactionRepository).save(argThat(t -> t.getStatus() == TransactionStatus.PENDING));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Posting>> postingCaptor = ArgumentCaptor.forClass(List.class);
        verify(postingRepository).saveAll(postingCaptor.capture());

        List<Posting> savedPostings = postingCaptor.getValue();
        assertThat(savedPostings).hasSize(2)
                .anyMatch(p -> p.getDirection() == PostingDirection.DEBIT &&
                        p.getAccount() == senderAccount)
                .anyMatch(p -> p.getDirection() == PostingDirection.CREDIT &&
                        p.getAccount() == withdrawalAccount);
    }

    @Test
    @DisplayName("Should throw InsufficientFundsException when user balance is too low")
    void testReserveFunds_InsufficientFunds() {
        ReservationRequest request = new ReservationRequest(senderId, new BigDecimal("150.00"), CurrencyType.USD, referenceId);

        when(accountRepository.findByUserIdAndCurrency(senderId, CurrencyType.USD)).thenReturn(Optional.of(senderAccount));
        when(accountRepository.findByNameAndCurrency("PENDING_WITHDRAWAL", CurrencyType.USD)).thenReturn(Optional.of(withdrawalAccount));

        assertThatThrownBy(() -> ledgerService.reserveFunds(request))
                .isInstanceOf(InsufficientFundsException.class);

        verify(transactionRepository, never()).save(any());
    }

    @Test
    @DisplayName("Should throw AccountInactiveException when user account is frozen")
    void testReserveFunds_AccountFrozen() {
        senderAccount.setStatus(AccountStatus.FROZEN);
        ReservationRequest request = new ReservationRequest(senderId, new BigDecimal("30.00"), CurrencyType.USD, referenceId);

        when(accountRepository.findByUserIdAndCurrency(senderId, CurrencyType.USD)).thenReturn(Optional.of(senderAccount));
        when(accountRepository.findByNameAndCurrency("PENDING_WITHDRAWAL", CurrencyType.USD)).thenReturn(Optional.of(withdrawalAccount));

        assertThatThrownBy(() -> ledgerService.reserveFunds(request))
                .isInstanceOf(AccountInactiveException.class);
    }

    @Test
    @DisplayName("Should exit silently if referenceId already exists (Idempotency)")
    void testReserveFunds_Idempotency() {
        ReservationRequest request = new ReservationRequest(senderId, new BigDecimal("30.00"), CurrencyType.USD, referenceId);

        when(transactionRepository.existsByReferenceId(referenceId.toString())).thenReturn(true);

        ledgerService.reserveFunds(request);

        verify(accountRepository, never()).save(any());
        verify(postingRepository, never()).save(any());
    }

    @Test
    @DisplayName("Should throw AccountNotFoundException if user account does not exist")
    void testReserveFunds_UserNotFound() {
        ReservationRequest request = new ReservationRequest(senderId, new BigDecimal("30.00"), CurrencyType.USD, referenceId);

        when(accountRepository.findByUserIdAndCurrency(senderId, CurrencyType.USD)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> ledgerService.reserveFunds(request))
                .isInstanceOf(AccountNotFoundException.class);
    }

    @Test
    @DisplayName("Should successfully release reserved funds back to the user")
    void testReleaseFunds_Success() {
        ReleaseRequest request = new ReleaseRequest(referenceId);

        when(transactionRepository.findByReferenceId(referenceId.toString())).thenReturn(Optional.of(pendingTransaction));
        when(postingRepository.findByTransactionAndDirection(pendingTransaction, PostingDirection.DEBIT))
                .thenReturn(Optional.of(originalDebit));
        when(accountRepository.findByNameAndCurrency("PENDING_WITHDRAWAL", CurrencyType.USD)).thenReturn(Optional.of(withdrawalAccount));

        ledgerService.releaseFunds(request);

        assertThat(senderAccount.getBalance()).isEqualByComparingTo("130.00");
        assertThat(withdrawalAccount.getBalance()).isEqualByComparingTo("-30.00");
        assertThat(pendingTransaction.getStatus()).isEqualTo(TransactionStatus.FAILED);
        assertThat(pendingTransaction.getMetadata()).contains("Funds released");

        verify(accountRepository).save(senderAccount);
        verify(accountRepository).save(withdrawalAccount);
        verify(transactionRepository).save(pendingTransaction);
        verify(postingRepository).saveAll(anyList());
    }

    @Test
    @DisplayName("Should do nothing if transaction is not in PENDING status")
    void testReleaseFunds_AlreadyProcessed() {
        pendingTransaction.setStatus(TransactionStatus.POSTED);
        ReleaseRequest request = new ReleaseRequest(referenceId);

        when(transactionRepository.findByReferenceId(referenceId.toString())).thenReturn(Optional.of(pendingTransaction));

        ledgerService.releaseFunds(request);

        verify(accountRepository, never()).save(any());
        verify(postingRepository, never()).saveAll(anyList());
        assertThat(pendingTransaction.getStatus()).isEqualTo(TransactionStatus.POSTED);
    }

    @Test
    @DisplayName("Should throw TransactionNotFoundException if referenceId is unknown")
    void testReleaseFunds_NotFound() {
        ReleaseRequest request = new ReleaseRequest(referenceId);
        when(transactionRepository.findByReferenceId(anyString())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> ledgerService.releaseFunds(request))
                .isInstanceOf(TransactionNotFoundException.class);
    }

    @Test
    @DisplayName("Should settle withdrawal: Debit Pending, Credit World Liquidity")
    void testProcessWithdrawal_Success() {
        WithdrawalConfirmedEvent event = new WithdrawalConfirmedEvent(referenceId, new WithdrawalPayload(new BigDecimal("30.00"), "USD"));

        when(transactionRepository.findByReferenceId(referenceId.toString())).thenReturn(Optional.of(pendingTransaction));
        when(postingRepository.findByTransactionAndDirection(pendingTransaction, PostingDirection.CREDIT)).thenReturn(Optional.of(pendingCredit));
        when(accountRepository.findByNameAndCurrency("WORLD_LIQUIDITY", CurrencyType.USD)).thenReturn(Optional.of(worldAccount));

        ledgerService.processWithdrawal(event);

        assertThat(withdrawalAccount.getBalance()).isEqualByComparingTo("-30.00");
        assertThat(worldAccount.getBalance()).isEqualByComparingTo("1030.00");
        assertThat(pendingTransaction.getStatus()).isEqualTo(TransactionStatus.POSTED);

        verify(accountRepository).save(withdrawalAccount);
        verify(accountRepository).save(worldAccount);
        verify(transactionRepository).save(pendingTransaction);
        verify(outboxRepository).save(any());
    }

    @Test
    @DisplayName("Should throw DataMismatchException if event amount differs from reserved amount")
    void testProcessWithdrawal_AmountMismatch() {
        WithdrawalConfirmedEvent event = new WithdrawalConfirmedEvent(referenceId, new WithdrawalPayload(new BigDecimal("50.00"),"USD"));

        when(transactionRepository.findByReferenceId(referenceId.toString())).thenReturn(Optional.of(pendingTransaction));
        when(postingRepository.findByTransactionAndDirection(pendingTransaction, PostingDirection.CREDIT)).thenReturn(Optional.of(pendingCredit));
        lenient().when(accountRepository.findByNameAndCurrency("WORLD_LIQUIDITY", CurrencyType.USD)).thenReturn(Optional.of(worldAccount));

        assertThatThrownBy(() -> ledgerService.processWithdrawal(event))
                .isInstanceOf(DataMismatchException.class);
    }

    @Test
    @DisplayName("Should throw IllegalStateException if transaction is not PENDING")
    void testProcessWithdrawal_WrongStatus() {
        pendingTransaction.setStatus(TransactionStatus.FAILED);
        WithdrawalConfirmedEvent event = new WithdrawalConfirmedEvent(referenceId, new WithdrawalPayload(new BigDecimal("50.00"), "USD"));

        when(transactionRepository.findByReferenceId(referenceId.toString())).thenReturn(Optional.of(pendingTransaction));

        assertThatThrownBy(() -> ledgerService.processWithdrawal(event))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("Should throw TransactionNotFoundException if referenceId is invalid")
    void testProcessWithdrawal_NotFound() {
        WithdrawalConfirmedEvent event = new WithdrawalConfirmedEvent(referenceId, new WithdrawalPayload(new BigDecimal("30.00"), "USD"));
        when(transactionRepository.findByReferenceId(anyString())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> ledgerService.processWithdrawal(event))
                .isInstanceOf(TransactionNotFoundException.class);
    }

    private Account createMockAccount(UUID id, String name, AccountType type, BigDecimal balance) {
        Account a = new Account();
        a.setId(UUID.randomUUID());
        a.setUserId(id);
        a.setName(name);
        a.setType(type);
        a.setBalance(balance);
        a.setStatus(AccountStatus.ACTIVE);
        a.setCurrency(CurrencyType.USD);
        return a;
    }

    private TransactionRequest createRequest(TransactionType type, BigDecimal amount) {
        TransactionRequest r = new TransactionRequest();
        r.setReferenceId(UUID.randomUUID().toString());
        r.setType(type);
        r.setSenderId(senderId);
        r.setReceiverId(receiverId);
        r.setAmount(amount);
        r.setCurrency(CurrencyType.USD);
        return r;
    }

    private void stubStrategyResolution(TransactionType type) {
        switch (type) {
            case TRANSFER, PAYMENT, ADJUSTMENT, REFUND -> {
                AccountPair pair = new AccountPair(senderAccount, receiverAccount);
                lenient().when(transferStrategy.resolve(any(), any())).thenReturn(pair);
            }
            case DEPOSIT -> {
                AccountPair pair = new AccountPair(worldAccount, receiverAccount);
                lenient().when(depositStrategy.resolve(any(), any())).thenReturn(pair);
            }
            case WITHDRAWAL -> {
                AccountPair pair = new AccountPair(senderAccount, worldAccount);
                lenient().when(withdrawalStrategy.resolve(any(), any())).thenReturn(pair);
            }
            case FEE -> {
                Account revenueAcc = createMockAccount(null, "REVENUE_ACCOUNT", AccountType.INCOME, BigDecimal.ZERO);
                AccountPair pair = new AccountPair(senderAccount, revenueAcc);
                lenient().when(feeStrategy.resolve(any(), any())).thenReturn(pair);
            }
            case INTEREST -> {
                Account expenseAcc = createMockAccount(null, "INTEREST_EXPENSE", AccountType.EXPENSE, BigDecimal.ZERO);
                AccountPair pair = new AccountPair(expenseAcc, receiverAccount);
                lenient().when(feeStrategy.resolve(any(), any())).thenReturn(pair);
            }
        }
    }

}
