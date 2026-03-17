package com.openfashion.ledgerservice.service;

import com.openfashion.ledgerservice.dto.TransactionRequest;

import java.util.List;

public interface LedgerBatchService {

//    void processBatch(List<PendingTransaction> batch);

//    List<UUID> findAlreadyProcessedIds(List<PendingTransaction> batch);

    void saveTransactions(List<TransactionRequest> batch);
}
