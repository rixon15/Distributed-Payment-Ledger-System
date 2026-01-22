package com.openfashion.ledgerservice.dto;

import com.openfashion.ledgerservice.model.CurrencyType;
import com.openfashion.ledgerservice.model.TransactionType;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Data;

import java.math.BigDecimal;
import java.util.UUID;

@Data
public class TransactionRequest {

    @NotNull(message = "Reference ID is required")
    private String referenceId;
    @NotNull(message = "Transaction Type is required")
    private TransactionType type;
    private UUID senderId;
    @NotNull(message = "Receiver ID is required")
    private UUID receiverId;
    @NotNull(message = "Amount is required")
    @Positive(message = "Amount must be greater than zero")
    private BigDecimal amount;
    @NotNull(message = "Currency is required")
    private CurrencyType currency;
    private String metadata;

}
