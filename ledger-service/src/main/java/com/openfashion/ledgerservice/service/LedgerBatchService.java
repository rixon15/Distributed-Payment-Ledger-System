package com.openfashion.ledgerservice.service;

import com.openfashion.ledgerservice.dto.TransactionRequest;

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
     * Persists insufficient-funds rejections generated during Redis pre-validation.
     *
     * @param nsfList requests rejected as NSF
     */
    void persistRejectedNsf(List<TransactionRequest> nsfList);
}
