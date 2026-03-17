package com.openfashion.ledgerservice.service;

import com.openfashion.ledgerservice.dto.TransactionRequest;
import com.openfashion.ledgerservice.dto.redis.PendingTransaction;
import com.openfashion.ledgerservice.model.Account;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

public interface RedisService {

    void bufferTransactions(PendingTransaction transaction);

    BigDecimal getPendingNetChanges(UUID accountId);

    void commitFromBuffer(List<PendingTransaction> transactions);

    void processBatchAtomic(List<TransactionRequest> batch);

    boolean waitForPersistence(List<TransactionRequest> batch, Duration time);

    void signalConfirmation(List<TransactionRequest> batch);

    List<TransactionRequest> popFromQueue(int batchSize);

    void initializeSnapshotIfMissing(Account account);
}
