package org.example.authorizationservice.service;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSVerifier;
import com.nimbusds.jose.crypto.ECDSAVerifier;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.ParseException;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.Set;


/**
 * Verifies the parts of a DPoP proof (RFC 9449) that can be checked away from the HTTP request it was sent with:
 * that the holder of the key a token is bound to signed it, recently, for that token.
 *
 * <p>'htm', 'htu' and 'jti' replay are left to the gateway, the only party that sees the original request.
 */
@Slf4j
@Component
public class DpopProofVerifier {

    static final Duration FRESHNESS_WINDOW = Duration.ofSeconds(60);

    private static final JOSEObjectType DPOP_TYPE = new JOSEObjectType("dpop+jwt");
    // FAPI 2.0 Security Profile, section 5.4: no RSASSA-PKCS1-v1_5 (RS256)
    private static final Set<JWSAlgorithm> ALLOWED_ALGORITHMS = Set.of(JWSAlgorithm.PS256, JWSAlgorithm.ES256);

    public boolean isValid(String proof, String opaqueToken, String expectedJkt) {
        String problem = findProblem(proof, opaqueToken, expectedJkt);

        if (problem != null) log.info("DPoP proof rejected: {}", problem);

        return problem == null;
    }

    private static @Nullable String findProblem(String proof, String opaqueToken, String expectedJkt) {
        try {
            SignedJWT jwt = SignedJWT.parse(proof);

            if (!DPOP_TYPE.equals(jwt.getHeader().getType())) return "wrong typ header";
            if (!ALLOWED_ALGORITHMS.contains(jwt.getHeader().getAlgorithm())) return "algorithm not allowed";

            JWK jwk = jwt.getHeader().getJWK();

            if (jwk == null) return "no embedded JWK";
            if (!jwk.computeThumbprint().toString().equals(expectedJkt)) return "key does not match the token binding";
            if (!jwt.verify(verifierFor(jwk))) return "invalid signature";

            JWTClaimsSet claims = jwt.getJWTClaimsSet();

            if (!isFresh(claims.getIssueTime())) return "not fresh";
            if (!accessTokenHash(opaqueToken).equals(claims.getStringClaim("ath"))) return "ath does not match the token";

            return null;
        } catch (ParseException | JOSEException e) {
            return "malformed (" + e.getMessage() + ")";
        }
    }

    private static JWSVerifier verifierFor(JWK jwk) throws JOSEException {
        return switch (jwk) {
            case ECKey ecKey -> new ECDSAVerifier(ecKey);
            case RSAKey rsaKey -> new RSASSAVerifier(rsaKey);
            default -> throw new JOSEException("unsupported key type " + jwk.getKeyType());
        };
    }

    private static boolean isFresh(@Nullable Date issueTime) {
        if (issueTime == null) return false;

        Instant issuedAt = issueTime.toInstant();
        Instant now = Instant.now();

        return !issuedAt.isBefore(now.minus(FRESHNESS_WINDOW)) && !issuedAt.isAfter(now.plus(FRESHNESS_WINDOW));
    }

    private static String accessTokenHash(String opaqueToken) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(opaqueToken.getBytes(StandardCharsets.US_ASCII));

            return Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
