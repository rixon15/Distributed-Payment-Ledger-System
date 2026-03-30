package com.openfashion.ledgerservice.core.exceptions;

public class DbTimeoutException extends RuntimeException {

    /**
     * Thrown when batch persistence confirmation is not observed within timeout.
     *
     * <p>Typically used to trigger safe retry behavior in ingestion flow.
     */
    public DbTimeoutException() {
        super("Db commit timeout - retrying batch");
    }

}
