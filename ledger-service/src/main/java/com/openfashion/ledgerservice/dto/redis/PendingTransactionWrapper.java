package com.openfashion.ledgerservice.dto.redis;

import lombok.AllArgsConstructor;

@AllArgsConstructor
public class PendingTransactionWrapper {
    private PendingTransaction data;
    private int retryCount;
    private long createdAt;

    public static PendingTransactionWrapper wrap(PendingTransaction data) {
        return new PendingTransactionWrapper(data, 0, System.currentTimeMillis());
    }

    public void increment() {
        this.createdAt++;
    }
}
