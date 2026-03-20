package com.openfashion.ledgerservice.service.strategy;

import com.openfashion.ledgerservice.dto.TransactionRequest;
import com.openfashion.ledgerservice.dto.event.TransactionInitiatedEvent;
import com.openfashion.ledgerservice.dto.event.TransactionPayload;
import com.openfashion.ledgerservice.model.CurrencyType;
import com.openfashion.ledgerservice.model.TransactionType;
import com.openfashion.ledgerservice.repository.AccountRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class WithdrawalStrategy extends LedgerStrategy {

    public WithdrawalStrategy(AccountRepository accountRepository) {
        super(accountRepository);
    }


    @Override
    public boolean supports(TransactionType transactionType) {
        return transactionType == TransactionType.WITHDRAWAL;
    }

    @Override
    public TransactionRequest mapToRequest(TransactionInitiatedEvent event) {

        TransactionPayload payload = event.payload();
        CurrencyType currencyType = payload.currency();

        TransactionRequest request = new TransactionRequest();
        request.setReferenceId(event.referenceId());
        request.setAmount(payload.amount());
        request.setCurrency(payload.currency());
        request.setSenderId(payload.senderId());
        request.setReceiverId(payload.receiverId());

        switch (payload.status()) {
            case PENDING -> {
                log.info("Mapping Withdrawal Reserve Phase for {}", event.referenceId());
                request.setType(TransactionType.WITHDRAWAL_RESERVE);
                request.setDebitAccountId(resolveUserAccount(payload.senderId(), currencyType));
                request.setCreditAccountId(resolveSystemAccount(PENDING_WITHDRAWAL_ACC, currencyType));
            }
            case POSTED -> {
                log.info("Mapping Withdrawal Settle Phase for {}", event.referenceId());
                request.setType(TransactionType.WITHDRAWAL_SETTLE);
                request.setDebitAccountId(resolveSystemAccount(PENDING_WITHDRAWAL_ACC, currencyType));
                request.setCreditAccountId(resolveSystemAccount(WORLD_LIQUIDITY_ACC, currencyType));
            }
            case FAILED -> {
                log.info("Mapping Withdrawal Release Phase for {}", event.referenceId());
                request.setType(TransactionType.WITHDRAWAL_RELEASE);
                request.setDebitAccountId(resolveSystemAccount(PENDING_WITHDRAWAL_ACC, currencyType));
                request.setCreditAccountId(resolveSystemAccount(WORLD_LIQUIDITY_ACC, currencyType));
            }
            default -> throw new IllegalArgumentException("Unknown withdrawal status: " + payload.status());
        }

        return request;
    }

}
