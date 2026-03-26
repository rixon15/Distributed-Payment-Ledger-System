package com.openfashion.ledgerservice.repository;

import com.openfashion.ledgerservice.model.Posting;
import com.openfashion.ledgerservice.model.Transaction;

import java.util.List;

public interface TransactionBatchRepository {
    int[] upsertTransactions(List<Transaction> transactions);
    void updateAccountBalances(List<Posting> filteredPostings);
}
