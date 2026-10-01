package org.example.authorizationservice.support;

import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.UUID;

/**
 * Builds DPoP proofs for tests. Defaults describe a valid proof; each wither breaks or changes one aspect of it.
 */
public final class DpopProofs {

    private final JWK signingKey;
    private JWK embeddedKey;
    private JWSAlgorithm algorithm;
    private String type = "dpop+jwt";
    private Instant issuedAt = Instant.now();
    private String accessToken;
    private String httpMethod;
    private String httpUri;

    public DpopProofs(JWK signingKey) {
        this.signingKey = signingKey;
        this.embeddedKey = signingKey.toPublicJWK();
        this.algorithm = signingKey instanceof ECKey ? JWSAlgorithm.ES256 : JWSAlgorithm.PS256;
    }

    public static DpopProofs signedBy(JWK key) {
        return new DpopProofs(key);
    }

    /**
     * Sets the token the proof is bound to through its 'ath' claim; without it the claim is left out.
     */
    public DpopProofs forAccessToken(String accessToken) {
        this.accessToken = accessToken;
        return this;
    }

    /**
     * Binds the proof to an HTTP request through its 'htm' and 'htu' claims; without it both are left out.
     */
    public DpopProofs forRequest(String httpMethod, String httpUri) {
        this.httpMethod = httpMethod;
        this.httpUri = httpUri;
        return this;
    }

    public DpopProofs embedding(JWK key) {
        this.embeddedKey = key;
        return this;
    }

    public DpopProofs algorithm(JWSAlgorithm algorithm) {
        this.algorithm = algorithm;
        return this;
    }

    public DpopProofs type(String type) {
        this.type = type;
        return this;
    }

    public DpopProofs issuedAt(Instant issuedAt) {
        this.issuedAt = issuedAt;
        return this;
    }

    public String build() {
        JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder()
                .jwtID(UUID.randomUUID().toString())
                .issueTime(Date.from(issuedAt));

        if (accessToken != null) claims.claim("ath", hash(accessToken));
        if (httpMethod != null) claims.claim("htm", httpMethod).claim("htu", httpUri);

        SignedJWT jwt = new SignedJWT(
                new JWSHeader.Builder(algorithm).type(new JOSEObjectType(type)).jwk(embeddedKey).build(),
                claims.build());

        try {
            jwt.sign(signer());
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }

        return jwt.serialize();
    }

    private JWSSigner signer() throws JOSEException {
        return switch (signingKey) {
            case ECKey ecKey -> new ECDSASigner(ecKey);
            case RSAKey rsaKey -> new RSASSASigner(rsaKey);
            default -> throw new IllegalArgumentException("Unsupported key type " + signingKey.getKeyType());
        };
    }

    private static String hash(String accessToken) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(accessToken.getBytes(StandardCharsets.US_ASCII));

            return Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
