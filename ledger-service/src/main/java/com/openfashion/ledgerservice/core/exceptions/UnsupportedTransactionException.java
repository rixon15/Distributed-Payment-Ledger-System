package com.openfashion.ledgerservice.core.exceptions;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.BAD_REQUEST)
public class UnsupportedTransactionException extends RuntimeException {
    public UnsupportedTransactionException(String type) {
        super("Unsupported transaction type: " + type);
    }
}
