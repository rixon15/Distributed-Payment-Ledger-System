package com.openfashion.ledgerservice.service;

import com.openfashion.ledgerservice.dto.ReleaseRequest;
import com.openfashion.ledgerservice.dto.ReservationRequest;
import com.openfashion.ledgerservice.dto.TransactionRequest;
import com.openfashion.ledgerservice.dto.event.WithdrawalCompleteEvent;

public interface LedgerService {

    void processTransaction(TransactionRequest request);

    void reserveFunds(ReservationRequest request);

    void releaseFunds(ReleaseRequest request);

    void processWithdrawal(WithdrawalCompleteEvent event);
}
