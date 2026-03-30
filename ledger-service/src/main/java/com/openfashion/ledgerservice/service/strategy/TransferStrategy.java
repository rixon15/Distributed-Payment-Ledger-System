package com.openfashion.ledgerservice.service.strategy;

import com.openfashion.ledgerservice.dto.TransactionRequest;
import com.openfashion.ledgerservice.dto.event.TransactionInitiatedEvent;
import com.openfashion.ledgerservice.dto.event.TransactionPayload;
import com.openfashion.ledgerservice.model.TransactionType;
import com.openfashion.ledgerservice.repository.AccountRepository;
import org.springframework.stereotype.Component;

/**
 * Maps user-to-user transfer/payment events into direct debit/credit postings.
 */
@Component
public class TransferStrategy extends LedgerStrategy {

    public TransferStrategy(AccountRepository accountRepository) {
        super(accountRepository);
    }

    /**
     * Supports {@code TRANSFER} and {@code PAYMENT} transaction types.
     */
    @Override
    public boolean supports(TransactionType transactionType) {
        return transactionType == TransactionType.TRANSFER ||
                transactionType == TransactionType.PAYMENT;
    }

    /**
     * Builds transfer request:
     * debit = sender account, credit = receiver account (same currency).
     */
    @Override
    public TransactionRequest mapToRequest(TransactionInitiatedEvent event) {

        TransactionPayload payload = event.payload();

        TransactionRequest request = new TransactionRequest();
        request.setReferenceId(event.referenceId());
        request.setType(event.eventType());
        request.setSenderId(payload.senderId());
        request.setReceiverId(payload.receiverId());
        request.setAmount(payload.amount());
        request.setCurrency(payload.currency());
        request.setDebitAccountId(resolveUserAccount(payload.senderId(), payload.currency()));
        request.setCreditAccountId(resolveUserAccount(payload.receiverId(), payload.currency()));

        return request;
    }

}
