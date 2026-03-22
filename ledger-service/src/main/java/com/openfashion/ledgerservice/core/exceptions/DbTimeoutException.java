package com.openfashion.ledgerservice.core.exceptions;

public class DbTimeoutException extends RuntimeException {

    public DbTimeoutException() {
        super("Db commit timeout - retrying batch");
    }

}
