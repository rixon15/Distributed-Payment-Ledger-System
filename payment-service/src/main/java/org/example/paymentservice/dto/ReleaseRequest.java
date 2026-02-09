package org.example.paymentservice.dto;

import java.util.UUID;

public record ReleaseRequest(
        UUID userId
) {
}
