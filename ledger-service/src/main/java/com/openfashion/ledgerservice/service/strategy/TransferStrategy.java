package com.openfashion.ledgerservice.service.strategy;

import com.openfashion.ledgerservice.core.exceptions.AccountNotFoundException;
import com.openfashion.ledgerservice.dto.TransactionRequest;
import com.openfashion.ledgerservice.dto.event.TransactionInitiatedEvent;
import com.openfashion.ledgerservice.dto.event.TransactionPayload;
import com.openfashion.ledgerservice.model.Account;
import com.openfashion.ledgerservice.model.TransactionType;
import com.openfashion.ledgerservice.repository.AccountRepository;
import org.springframework.stereotype.Component;

@Component
public class TransferStrategy extends LedgerStrategy {

    public TransferStrategy(AccountRepository accountRepository) {
        super(accountRepository);
    }

    @Override
    public boolean supports(TransactionType transactionType) {
        return transactionType == TransactionType.TRANSFER ||
                transactionType == TransactionType.PAYMENT;
    }

    @Override
    public TransactionRequest mapToRequest(TransactionInitiatedEvent event) {

        TransactionPayload payload = event.payload();

        Account debit = accountRepository.findByUserIdAndCurrency(payload.senderId(), payload.currency())
                .orElseThrow(() -> new AccountNotFoundException(payload.senderId()));
        Account credit = accountRepository.findByUserIdAndCurrency(payload.receiverId(), payload.currency())
                .orElseThrow(() -> new AccountNotFoundException(payload.receiverId()));


        TransactionRequest request = new TransactionRequest();
        request.setReferenceId(event.referenceId());
        request.setType(event.eventType());
        request.setSenderId(payload.senderId());
        request.setReceiverId(payload.receiverId());
        request.setAmount(payload.amount());
        request.setCurrency(payload.currency());
        request.setDebitAccountId(debit.getId());
        request.setCreditAccountId(credit.getId());

        return request;
    }

}
