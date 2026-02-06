package org.example.paymentservice.core.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.CONFLICT)
public class DuplicatedRequestException extends RuntimeException {
    public DuplicatedRequestException(String idempotencyKey) {
        super("Duplicated request: " + idempotencyKey);
    }
}
