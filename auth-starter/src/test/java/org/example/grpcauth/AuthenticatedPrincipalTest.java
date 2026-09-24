package org.example.grpcauth;

import org.example.grpcauth.exception.InsufficientScopeException;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.*;

class AuthenticatedPrincipalTest {

    private static final Instant ISSUED_AT = Instant.parse("2026-09-23T10:00:00Z");
    private static final Instant EXPIRES_AT = ISSUED_AT.plusSeconds(60);


    private static AuthenticatedPrincipal principalWithScopes(Set<String> scopes) {
        return new AuthenticatedPrincipal("7b0c4f3e-1d2a-4c5b-9e8f-0a1b2c3d4e5f", Set.of("ledger-service"), scopes,
                "jti-1", ISSUED_AT, EXPIRES_AT);
    }

    @Test
    void checkGrantedScopes() {
        AuthenticatedPrincipal principal = principalWithScopes(Set.of("ledger:read", "ledger:write"));

        assertThat(principal.hasScope("ledger:read")).isTrue();
        assertThat(principal.hasScope("ledger:admin")).isFalse();
        assertThatCode(() -> principal.requireScope("ledger:write")).doesNotThrowAnyException();
    }

    @Test
    void requireScopeThrowsWithTheMissingScope() {
        AuthenticatedPrincipal principal = principalWithScopes(Set.of("ledger:read"));

        assertThatThrownBy(() -> principal.requireScope("ledger:write"))
                .isInstanceOfSatisfying(InsufficientScopeException.class,
                        e -> assertThat(e.getRequiredScope()).isEqualTo("ledger:write"))
                .hasMessage("Missing required scope: ledger:write");
    }

    @Test
    void copiesCollectionsDefensively() {
        Set<String> audience = new HashSet<>(Set.of("ledger-service"));
        Set<String> scopes = new HashSet<>(Set.of("ledger:read"));
        AuthenticatedPrincipal principal = new AuthenticatedPrincipal("subject", audience, scopes, "jti-1",
                ISSUED_AT, EXPIRES_AT);

        audience.add("payment-service");
        scopes.add("ledger:write");

        assertThat(principal.audience()).containsExactly("ledger-service");
        assertThat(principal.hasScope("ledger:write")).isFalse();
        assertThatThrownBy(() -> principal.scopes().add("ledger:write"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void rejectsMissingRequiredComponents() {
        Set<String> audience = Set.of("ledger-service");
        Set<String> scopes = Set.of();

        assertThatThrownBy(() -> new AuthenticatedPrincipal(null, audience, scopes, "jti-1", ISSUED_AT, EXPIRES_AT))
                .isInstanceOf(NullPointerException.class).hasMessage("subject");
        assertThatThrownBy(() -> new AuthenticatedPrincipal("subject", audience, scopes, null, ISSUED_AT, EXPIRES_AT))
                .isInstanceOf(NullPointerException.class).hasMessage("tokenId");
        assertThatThrownBy(() -> new AuthenticatedPrincipal("subject", audience, scopes, "jti-1", null, EXPIRES_AT))
                .isInstanceOf(NullPointerException.class).hasMessage("issuedAt");
        assertThatThrownBy(() -> new AuthenticatedPrincipal("subject", audience, scopes, "jti-1", ISSUED_AT, null))
                .isInstanceOf(NullPointerException.class).hasMessage("expiresAt");
        assertThatThrownBy(() -> new AuthenticatedPrincipal("subject", null, scopes, "jti-1", ISSUED_AT, EXPIRES_AT))
                .isInstanceOf(NullPointerException.class).hasMessage("audience");
        assertThatThrownBy(() -> new AuthenticatedPrincipal("subject", audience, null, "jti-1", ISSUED_AT, EXPIRES_AT))
                .isInstanceOf(NullPointerException.class).hasMessage("scopes");
    }
}
