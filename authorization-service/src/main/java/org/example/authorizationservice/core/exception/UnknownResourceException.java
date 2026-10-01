package org.example.authorizationservice.core.exception;


/**
 * A token was requested for a resource that is not a configured internal audience. This is a caller bug, not a problem
 * with the token being exchanged.
 */
public class UnknownResourceException extends RuntimeException {

    public UnknownResourceException(String resource) {
        super("Unknown resource '%s'".formatted(resource));
    }
}
