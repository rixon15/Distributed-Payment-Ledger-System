package com.openfashion.ledgerservice.service.imp;

import com.openfashion.ledgerservice.dto.TransactionRequest;

public interface LedgerService {

    void processTransaction(TransactionRequest request);

}
