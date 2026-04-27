package com.openfashion.ledgerservice.service;

import com.openfashion.ledgerservice.dto.TransactionRequest;
import com.openfashion.ledgerservice.model.TransactionStatus;

import java.util.List;

/**
 * Postgres persistence boundary for normalized ledger transaction batches.
 *
 * <p>Implementations persist posted transactions/postings/outbox events and
 * persist NSF rejections as rejected transactions with response outbox events.
 */
public interface LedgerBatchService {

    /**
     * Persists a batch of accepted ledger requests.
     *
     * @param batch accepted requests from Redis staging
     */
    void saveTransactions(List<TransactionRequest> batch);

    /**
     * Persists rejected ledger requests as failed transactions with respective status reasons.
     * <p>Unlike accepted transactions, rejected requests do not create postings or mutate
     * account balances. Instead, a single {@code Transaction} record stores the rejection reason
     * and a corresponding {@code OutboxEvent} is emitted for response publication.
     * <p>Common rejection reasons are:
     * <ul>
     *     <li>{@code REJECTED_NSF} – insufficient funds (caught by Redis Lua script)</li>
     *     <li>{@code REJECTED_VALIDATION} – business rule violation (caught by listener strategy validation)</li>
     * </ul>
     * <p>Idempotent: duplicate rejection requests (same reference ID + transaction type) are
     * skipped to prevent duplicate outbox records.
     * @param rejectedList list of transaction requests that were rejected
     * @param reason the {@code TransactionStatus} enum value indicating rejection cause
     */
    void persistRejected(List<TransactionRequest> rejectedList, TransactionStatus reason);
}
