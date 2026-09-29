package org.example.grpcauth.exception;

/**
 * Thrown when a token can't be verified because no signing keys can be obtained: the JWK Set endpoint is
 * unreachable and nothing usable is cached.
 *
 * <p>This says nothing about the token itself, so the caller receives the retryable {@code UNAVAILABLE} rather than
 * {@code UNAUTHENTICATED}.
 */
public class JwksUnavailableException extends Exception {

    public JwksUnavailableException(Throwable cause) {
        super("JWK Set unavailable: " + cause.getMessage(), cause);
    }
}
