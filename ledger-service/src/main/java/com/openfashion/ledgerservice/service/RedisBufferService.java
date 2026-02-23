package com.openfashion.ledgerservice.service;

import com.openfashion.ledgerservice.dto.redis.PendingTransaction;

import java.math.BigDecimal;
import java.util.UUID;

public interface RedisBufferService {

    void bufferTransactions(PendingTransaction transaction);

    BigDecimal getPendingNetChanges(UUID accountId);
}
