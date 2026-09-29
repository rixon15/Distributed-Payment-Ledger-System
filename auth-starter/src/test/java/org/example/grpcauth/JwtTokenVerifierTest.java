package org.example.grpcauth;

import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.jwk.source.RateLimitReachedException;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.PlainJWT;
import com.nimbusds.jwt.SignedJWT;
import org.example.grpcauth.exception.InvalidTokenException;
import org.example.grpcauth.exception.JwksUnavailableException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.example.grpcauth.TestTokens.*;

class JwtTokenVerifierTest {

    private static final Instant NOW = Instant.parse("2026-09-24T12:00:00Z");
    private static final RSAKey KEY = TestTokens.rsaKey("key-1");

    private final JwtTokenVerifier verifier = verifier(new ImmutableJWKSet<>(new JWKSet(KEY.toPublicJWK())));

    private static JwtTokenVerifier verifier(JWKSource<SecurityContext> keySource) {
        return new JwtTokenVerifier(TestTokens.properties(Map.of()), keySource, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private void assertRejected(String token, String reason) {
        assertThatThrownBy(() -> verifier.verify(token))
                .isInstanceOf(InvalidTokenException.class)
                .hasMessageContaining(reason);
    }

    @Test
    void acceptsValidToken() throws Exception {
        JWTClaimsSet claims = claims(NOW).build();

        AuthenticatedPrincipal principal = verifier.verify(sign(KEY, claims));

        assertThat(principal.subject()).isEqualTo(SUBJECT);
        assertThat(principal.audience()).containsExactly(AUDIENCE);
        assertThat(principal.scopes()).containsExactly("ledger:read");
        assertThat(principal.tokenId()).isEqualTo(claims.getJWTID());
        assertThat(principal.issuedAt()).isEqualTo(NOW);
        assertThat(principal.expiresAt()).isEqualTo(NOW.plusSeconds(60));
    }

    @Test
    void acceptsAudienceListContainingThisService() throws Exception {
        String token = sign(KEY, claims(NOW).audience(List.of("payment-service", AUDIENCE)).build());

        assertThat(verifier.verify(token).audience()).containsExactlyInAnyOrder("payment-service", AUDIENCE);
    }

    @Test
    void parsesSpaceDelimitedScopeString() throws Exception {
        String token = sign(KEY, claims(NOW).claim("scope", "ledger:read  ledger:write").build());

        assertThat(verifier.verify(token).scopes()).containsExactlyInAnyOrder("ledger:read", "ledger:write");
    }

    @Test
    void treatsMissingOrEmptyScopeAsNoScopes() throws Exception {
        String missing = sign(KEY, claims(NOW).claim("scope", null).build());
        String empty = sign(KEY, claims(NOW).claim("scope", "").build());

        assertThat(verifier.verify(missing).scopes()).isEmpty();
        assertThat(verifier.verify(empty).scopes()).isEmpty();
    }

    @Test
    void rejectsScopeOfWrongType() {
        assertRejected(sign(KEY, claims(NOW).claim("scope", 42).build()), "scope claim must be");
        assertRejected(sign(KEY, claims(NOW).claim("scope", List.of("ledger:read", 1)).build()),
                "scope array must contain only non-blank strings");
    }

    @Test
    void rejectsMissingOrWrongTokenType() {
        assertRejected(sign(KEY, header(KEY).type(null).build(), claims(NOW).build()), "typ");
        assertRejected(sign(KEY, header(KEY).type(JOSEObjectType.JWT).build(), claims(NOW).build()), "typ");
    }

    @ParameterizedTest
    @ValueSource(strings = {"application/at+jwt", "AT+JWT"})
    void acceptsEquivalentTokenTypeSpellings(String type) throws Exception {
        String token = sign(KEY, header(KEY).type(new JOSEObjectType(type)).build(), claims(NOW).build());

        assertThat(verifier.verify(token).subject()).isEqualTo(SUBJECT);
    }

    @Test
    void rejectsUnsignedToken() {
        // Carries the right 'typ', so it gets past the type check and is rejected for being unsigned
        PlainHeader header = new PlainHeader.Builder().type(TestTokens.AT_JWT).build();
        String token = new PlainJWT(header, claims(NOW).build()).serialize();

        assertRejected(token, "Unsecured (plain) JWTs are rejected");
    }

    @Test
    void rejectsHmacTokenEvenWhenKeyedWithThePublicKey() throws Exception {
        // Classic algorithm confusion: an HMAC keyed with bytes the attacker can read
        byte[] secret = KEY.toPublicJWK().toRSAPublicKey().getEncoded();
        SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.HS256).type(TestTokens.AT_JWT)
                .keyID(KEY.getKeyID()).build(), claims(NOW).build());
        jwt.sign(new MACSigner(secret));

        assertRejected(jwt.serialize(), "Another algorithm expected, or no matching key(s) found");
    }

    @Test
    void rejectsAlgorithmOutsideTheAllowlist() {
        JWSHeader rs256 = new JWSHeader.Builder(JWSAlgorithm.RS256).type(TestTokens.AT_JWT)
                .keyID(KEY.getKeyID()).build();

        assertRejected(sign(KEY, rs256, claims(NOW).build()), "Another algorithm expected");
    }

    @Test
    void rejectsUnknownKeyId() {
        RSAKey other = TestTokens.rsaKey("key-2");

        assertRejected(sign(other, claims(NOW).build()), "no matching key(s) found");
    }

    @Test
    void rejectsSignatureByDifferentKeyUnderKnownKeyId() {
        RSAKey impostor = TestTokens.rsaKey(KEY.getKeyID());

        assertRejected(sign(impostor, claims(NOW).build()), "Invalid signature");
    }

    @Test
    void rejectsMalformedToken() {
        assertRejected("not-a-jwt", "Invalid JWT serialization");
    }

    @Test
    void rejectsWrongOrMissingIssuer() {
        assertRejected(sign(KEY, claims(NOW).issuer("http://evil.example").build()), "iss claim has value");
        assertRejected(sign(KEY, claims(NOW).issuer(null).build()), "missing required claims: [iss]");
    }

    @Test
    void rejectsWrongOrMissingAudience() {
        assertRejected(sign(KEY, claims(NOW).audience("payment-service").build()), "audience rejected");
        assertRejected(sign(KEY, claims(NOW).audience((String) null).build()), "missing required audience");
    }

    @ParameterizedTest
    @ValueSource(strings = {"sub", "exp", "iat", "jti"})
    void rejectsMissingRequiredClaim(String claim) {
        JWTClaimsSet claims = claims(NOW).claim(claim, null).build();

        assertRejected(sign(KEY, claims), "missing required claims: [" + claim + "]");
    }

    @Test
    void rejectsBlankSubjectOrTokenId() {
        assertRejected(sign(KEY, claims(NOW).subject(" ").build()), "sub claim must not be blank");
        assertRejected(sign(KEY, claims(NOW).jwtID("").build()), "jti claim must not be blank");
    }

    @Test
    void rejectsExpiredToken() {
        Instant issued = NOW.minusSeconds(120);
        JWTClaimsSet claims = claims(issued).expirationTime(Date.from(NOW.minusSeconds(31))).build();

        assertRejected(sign(KEY, claims), "Expired JWT");
    }

    @Test
    void acceptsTokenExpiredWithinClockSkew() throws Exception {
        Instant issued = NOW.minusSeconds(70);
        JWTClaimsSet claims = claims(issued).expirationTime(Date.from(NOW.minusSeconds(10))).build();

        assertThat(verifier.verify(sign(KEY, claims)).subject()).isEqualTo(SUBJECT);
    }

    @Test
    void rejectsTokenNotYetValid() {
        JWTClaimsSet claims = claims(NOW).notBeforeTime(Date.from(NOW.plusSeconds(31))).build();

        assertRejected(sign(KEY, claims), "before use time");
    }

    @Test
    void rejectsTokenIssuedInTheFuture() {
        Instant issued = NOW.plusSeconds(31);

        assertRejected(sign(KEY, claims(issued).build()), "issued in the future");
    }

    @Test
    void acceptsTokenIssuedSlightlyAheadWithinClockSkew() throws Exception {
        Instant issued = NOW.plusSeconds(10);

        assertThat(verifier.verify(sign(KEY, claims(issued).build())).issuedAt()).isEqualTo(issued);
    }

    @Test
    void rejectsTokenOlderThanMaxAgeEvenIfNotExpired() {
        // A misconfigured issuer handing out long-lived tokens: 'exp' alone would accept this
        Instant issued = NOW.minusSeconds(5 * 60 + 31);
        JWTClaimsSet claims = claims(issued).expirationTime(Date.from(NOW.plusSeconds(3600))).build();

        assertRejected(sign(KEY, claims), "older than max token age");
    }

    @Test
    void acceptsTokenAtMaxAgeWithinClockSkew() throws Exception {
        Instant issued = NOW.minusSeconds(5 * 60 + 29);
        JWTClaimsSet claims = claims(issued).expirationTime(Date.from(NOW.plusSeconds(60))).build();

        assertThat(verifier.verify(sign(KEY, claims)).issuedAt()).isEqualTo(issued);
    }

    // --- key source failures ---

    @Test
    void reportsUnreachableKeySourceAsUnavailable() {
        JwtTokenVerifier unreachable = verifier((selector, context) -> {
            throw new KeySourceException("Connection refused");
        });

        assertThatThrownBy(() -> unreachable.verify(sign(KEY, claims(NOW).build())))
                .isInstanceOf(JwksUnavailableException.class)
                .hasMessageContaining("Connection refused");
    }

    @Test
    void reportsRateLimitedRefetchAsInvalidToken() {
        JwtTokenVerifier rateLimited = verifier((selector, context) -> {
            throw new RateLimitReachedException();
        });

        assertThatThrownBy(() -> rateLimited.verify(sign(KEY, claims(NOW).build())))
                .isInstanceOf(InvalidTokenException.class)
                .hasMessageContaining("rate-limited");
    }

    @Test
    void publicConstructorUsesSystemClock() throws Exception {
        JwtTokenVerifier systemClock = new JwtTokenVerifier(TestTokens.properties(Map.of()),
                new ImmutableJWKSet<>(new JWKSet(KEY.toPublicJWK())));

        assertThat(systemClock.verify(sign(KEY, claims(Instant.now()).build())).scopes())
                .isEqualTo(Set.of("ledger:read"));
    }
}
