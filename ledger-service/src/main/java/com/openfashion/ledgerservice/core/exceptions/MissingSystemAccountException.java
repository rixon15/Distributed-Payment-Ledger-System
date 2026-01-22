package com.openfashion.ledgerservice.core.exceptions;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.NOT_FOUND)
public class MissingSystemAccountException extends RuntimeException {
    public MissingSystemAccountException(String name) {
        super("No system account found with the name: " + name);
    }
}
