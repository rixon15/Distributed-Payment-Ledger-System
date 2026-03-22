package com.openfashion.ledgerservice.service;

import com.openfashion.ledgerservice.dto.TransactionRequest;

import java.util.List;

public interface LedgerBatchService {

    void saveTransactions(List<TransactionRequest> batch);

    void persistRejectedNsf(List<TransactionRequest> nsfList);
}
