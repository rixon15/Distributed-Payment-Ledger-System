package com.openfashion.ledgerservice.service;

import com.openfashion.ledgerservice.dto.redis.PendingTransaction;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public interface RedisService {

    void bufferTransactions(PendingTransaction transaction);

    BigDecimal getPendingNetChanges(UUID accountId);

    void commitFromBuffer(List<PendingTransaction> transactions);
}
