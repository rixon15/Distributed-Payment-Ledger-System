package com.openfashion.ledgerservice.core.exceptions;

import com.openfashion.ledgerservice.model.PostingDirection;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

import java.util.UUID;

@ResponseStatus(HttpStatus.NOT_FOUND)
public class PostingNotFoundException extends RuntimeException {
    public PostingNotFoundException(UUID transactionId, PostingDirection direction) {
        super("Cannot find" + direction + " posting for transaction: " + transactionId);
    }
}
