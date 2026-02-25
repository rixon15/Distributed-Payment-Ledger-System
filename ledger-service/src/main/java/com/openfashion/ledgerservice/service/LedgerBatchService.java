package com.openfashion.ledgerservice.service;

import com.openfashion.ledgerservice.dto.redis.PendingTransaction;

import java.util.List;
import java.util.UUID;

public interface LedgerBatchService {

    void processBatch(List<PendingTransaction> batch);

    List<UUID> findAlreadyProcessedIds(List<PendingTransaction> batch);
}
