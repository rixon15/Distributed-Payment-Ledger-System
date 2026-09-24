package org.example.grpcauth;

import org.example.grpcauth.exception.InsufficientScopeException;

import java.time.Instant;
import java.util.Objects;
import java.util.Set;

/**
 * Verified identity of the caller, built from a JWT that passed every check in the verifier.
 *
 * @param subject   the {@code sub} claim; for internal tokens this is the user's id
 * @param audience  the {@code aud} claim
 * @param scopes    granted scopes, from the {@code scope} claim
 * @param tokenId   the {@code jti} claim
 * @param issuedAt  the {@code iat} claim
 * @param expiresAt the {@code exp} claim
 */
public record AuthenticatedPrincipal(
        String subject,
        Set<String> audience,
        Set<String> scopes,
        String tokenId,
        Instant issuedAt,
        Instant expiresAt
) {

    public AuthenticatedPrincipal {
        Objects.requireNonNull(subject, "subject");
        Objects.requireNonNull(audience, "audience");
        Objects.requireNonNull(scopes, "scopes");
        Objects.requireNonNull(tokenId, "tokenId");
        Objects.requireNonNull(issuedAt, "issuedAt");
        Objects.requireNonNull(expiresAt, "expiresAt");
        audience = Set.copyOf(audience);
        scopes = Set.copyOf(scopes);
    }

    public boolean hasScope(String scope) {
        return scopes.contains(scope);
    }

    /**
     * @throws InsufficientScopeException if the scope was not granted; translated to {@code PERMISSION_DENIED}
     */
    public void requireScope(String scope) {
        if(!hasScope(scope)) throw new InsufficientScopeException(scope);
    }

}
