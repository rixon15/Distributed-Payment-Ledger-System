package com.openfashion.ledgerservice.service.strategy;

import com.openfashion.ledgerservice.core.util.MoneyUtil;
import com.openfashion.ledgerservice.dto.TransactionRequest;
import com.openfashion.ledgerservice.dto.event.TransactionInitiatedEvent;
import com.openfashion.ledgerservice.dto.event.TransactionPayload;
import com.openfashion.ledgerservice.model.TransactionType;
import com.openfashion.ledgerservice.repository.AccountRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * Maps deposit events into postings from system liquidity to user account.
 */
@Component
@Slf4j
public class DepositStrategy extends LedgerStrategy {

    public DepositStrategy(AccountRepository accountRepository) {
        super(accountRepository);
    }


    /**
     * Supports {@code DEPOSIT} transaction type.
     */
    @Override
    public boolean supports(TransactionType transactionType) {
        return transactionType == TransactionType.DEPOSIT;
    }

    @Override
    public boolean isValidTransaction(TransactionInitiatedEvent event) {
        return event.payload().senderId() != null &&
                event.payload().receiverId() != null &&
                event.payload().senderId().equals(event.payload().receiverId()) &&
                event.payload().amount() != null &&
                event.payload().amount().compareTo(BigDecimal.ZERO) > 0;
    }

    /**
     * Builds a deposit request:
     * debit = {@code WORLD_LIQUIDITY}, credit = receiver user account.
     */
    @Override
    public TransactionRequest mapToRequest(TransactionInitiatedEvent event) {

        TransactionPayload payload = event.payload();

        TransactionRequest request = new TransactionRequest();
        request.setReferenceId(event.referenceId());
        request.setType(event.eventType());
        request.setSenderId(payload.senderId());
        request.setReceiverId(payload.receiverId());
        request.setAmount(MoneyUtil.format(payload.amount()));
        request.setCurrency(payload.currency());
        request.setDebitAccountId(resolveSystemAccount(WORLD_LIQUIDITY_ACC, payload.currency()));
        request.setCreditAccountId(resolveUserAccount(payload.receiverId(), payload.currency()));

        log.info("Mapped Deposit for reference: {}: {} {}",
                event.referenceId(), payload.amount(), payload.currency());

        return request;
    }
}
