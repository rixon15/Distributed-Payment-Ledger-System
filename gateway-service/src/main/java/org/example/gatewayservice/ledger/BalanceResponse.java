package org.example.gatewayservice.ledger;

import java.time.Instant;

public record BalanceResponse(
        String accountId,
        String currency,
        String amount,
        String status,
        Instant updatedAt
) {
}
