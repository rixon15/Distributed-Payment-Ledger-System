package com.openfashion.ledgerservice.dto;

import com.openfashion.ledgerservice.model.CurrencyType;
import com.openfashion.ledgerservice.model.TransactionType;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Data;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Normalized internal ledger command produced by strategy mapping.
 *
 * <p>This DTO is the handoff format between Kafka event ingestion, Redis staging,
 * and final Postgres persistence. By the time it is persisted, debit and credit
 * account ids should already be resolved.
 */
@Data
public class TransactionRequest {

    /** Upstream business reference, typically the originating payment id. */
    @NotNull(message = "Reference ID is required")
    private UUID referenceId;
    @NotNull(message = "Transaction Type is required")
    private TransactionType type;
    private UUID senderId;
    private UUID receiverId;
    @NotNull(message = "Amount is required")
    @Positive(message = "Amount must be greater than zero")
    private BigDecimal amount;
    @NotNull(message = "Currency is required")
    private CurrencyType currency;
    private String metadata;
    /** Resolved debit side of the posting pair. */
    private UUID debitAccountId;
    /** Resolved credit side of the posting pair. */
    private UUID creditAccountId;
}
