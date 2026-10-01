package org.example.grpcauth.exception;

/**
 * Thrown when an authenticated caller lacks a scope the called method requires.
 *
 * <p>The message names the missing scope for server-side logs only; the caller receives a generic
 * {@code PERMISSION_DENIED}.
 */
public class InsufficientScopeException extends RuntimeException {

    private final String requiredScope;

    public InsufficientScopeException(String requiredScope) {
        super("Missing required scope: " + requiredScope);
        this.requiredScope = requiredScope;
    }

    public String getRequiredScope() {
        return requiredScope;
    }
}
