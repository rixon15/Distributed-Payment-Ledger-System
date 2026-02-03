package com.openfashion.ledgerservice.service;

import com.openfashion.ledgerservice.dto.TransactionRequest;

public interface LedgerService {

    void processTransaction(TransactionRequest request);

}
