package org.example.grpcauth.exception;

/**
 * Thrown when a bearer token fails verification.
 *
 * <p>The message says which check failed, for server-side logs only; the caller receives a generic
 * {@code UNAUTHENTICATED}. Checked on purpose, so a caller of the verifier can't forget to map it.
 */
public class InvalidTokenException extends Exception {

    public InvalidTokenException(String message) {
        super(message);
    }

    public InvalidTokenException(String message, Throwable cause) {
        super(message, cause);
    }
}
