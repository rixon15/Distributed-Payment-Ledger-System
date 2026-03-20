package com.openfashion.ledgerservice.service;

import com.openfashion.ledgerservice.dto.TransactionRequest;
import com.openfashion.ledgerservice.model.Account;

import java.time.Duration;
import java.util.List;

public interface RedisService {
    void processBatchAtomic(List<TransactionRequest> batch);

    boolean waitForPersistence(List<TransactionRequest> batch, Duration time);

    void signalConfirmation(List<TransactionRequest> batch);

    List<TransactionRequest> popFromQueue(int batchSize);

    void initializeSnapshotIfMissing(Account account);
}
