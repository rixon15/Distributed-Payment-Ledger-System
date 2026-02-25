package com.openfashion.ledgerservice.service;

import com.openfashion.ledgerservice.dto.TransactionRequest;
import com.openfashion.ledgerservice.dto.event.WithdrawalEvent;

public interface LedgerService {

    void processTransaction(TransactionRequest request);

    void handleWithdrawalEvent(WithdrawalEvent event);
}
