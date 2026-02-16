package com.openfashion.ledgerservice.core.exceptions;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

import java.util.UUID;

@ResponseStatus(HttpStatus.FORBIDDEN)
public class AccountInactiveException extends RuntimeException {
    public AccountInactiveException(UUID accountId) {
        super("Account inactive: " + accountId);
    }
}
