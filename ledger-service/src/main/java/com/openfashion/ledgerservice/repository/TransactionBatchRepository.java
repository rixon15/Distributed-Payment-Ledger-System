package com.openfashion.ledgerservice.repository;

import com.openfashion.ledgerservice.model.Posting;
import com.openfashion.ledgerservice.model.Transaction;

import java.util.List;

/**
 * JDBC batch persistence contract for high-throughput ledger writes.
 *
 * <p>This abstraction exists because the core write path needs efficient transaction
 * upserts and grouped account balance updates beyond standard JPA save patterns.
 */
public interface TransactionBatchRepository {
    /**
     * Inserts candidate transactions using idempotent upsert semantics.
     *
     * @param transactions candidate transactions for insertion
     * @return batch update result aligned to input order
     */
    int[] upsertTransactions(List<Transaction> transactions);

    /**
     * Applies aggregated net balance changes derived from persisted postings.
     *
     * @param filteredPostings postings confirmed as newly persisted
     */
    void updateAccountBalances(List<Posting> filteredPostings);
}
