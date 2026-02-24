package com.openfashion.ledgerservice.service;

import com.openfashion.ledgerservice.dto.redis.PendingTransaction;

import java.util.List;

public interface LedgerBatchService {

    void processBatch(List<PendingTransaction> batch);

}
