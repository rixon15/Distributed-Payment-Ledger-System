package com.openfashion.ledgerservice.dto.redis;

import java.util.List;

/**
 * Result of acknowledging one or more Redis stream entries.
 *
 * @param requested number of entries requested for ACK
 * @param acked number of entries actually acknowledged
 * @param missingIds identifiers involved in a partial or failed ACK
 * @param success whether the ACK operation fully succeeded
 * @param error optional diagnostic message
 */
public record AckResult(
        int requested,
        int acked,
        List<String> missingIds,
        boolean success,
        String error
) {
}
