package org.example.gatewayservice.auth;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.crypto.ECDSAVerifier;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.example.gatewayservice.auth.exception.DpopValidationException;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.ParseException;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;


@Component
public class DpopProofValidator {

    static final Duration FRESHNESS_WINDOW = Duration.ofSeconds(60);
    private static final JOSEObjectType DPOP_TYPE = new JOSEObjectType("dpop+jwt");

    public String validate(DpopValidationRequest request) {
        SignedJWT proof = parse(request.proof());

        if (!DPOP_TYPE.equals(proof.getHeader().getType()))
            throw new DpopValidationException("DPoP proof has wrong typ header");

        JWK jwk = extractJwk(proof);

        if (!computeThumbprint(jwk).equals(request.expectedJkt()))
            throw new DpopValidationException("DPoP proof key does not match token binding");

        verifySignature(proof, jwk);

        JWTClaimsSet claims = extractClaims(proof);

        if (!request.httpMethod().equals(claims.getClaim("htm")))
            throw new DpopValidationException("DPoP proof htm does not match request method");
        if (!request.httpUri().equals(claims.getClaim("htu")))
            throw new DpopValidationException("DPoP proof htu does not match request URI");

        validateFreshness(claims.getIssueTime());
        validateAth(claims, request.opaqueToken());

        Object jti = claims.getClaim("jti");
        if (!(jti instanceof String jtiValue) || jtiValue.isBlank())
            throw new DpopValidationException("DPoP proof missing jti");

        return jtiValue;
    }


    private SignedJWT parse(String proof) {
        try {
            return SignedJWT.parse(proof);
        } catch (ParseException _) {
            throw new DpopValidationException("Malformed DPoP proof");
        }
    }

    private JWK extractJwk(SignedJWT proof) {
        JWK jwk = proof.getHeader().getJWK();
        if (jwk == null) throw new DpopValidationException("DPoP proof missing embedded JWK");

        return jwk;
    }

    private String computeThumbprint(JWK jwk) {
        try {
            return jwk.computeThumbprint().toString();
        } catch (JOSEException _) {
            throw new DpopValidationException("Unable to compute JWK thumbprint");
        }
    }

    private void verifySignature(SignedJWT proof, JWK jwk) {
        try {
            boolean valid = switch (jwk) {
                case ECKey ecKey -> proof.verify(new ECDSAVerifier(ecKey));
                case RSAKey rsaKey -> proof.verify(new RSASSAVerifier(rsaKey));
                default -> throw new DpopValidationException("Unsupported DPoP key type");
            };

            if (!valid) throw new DpopValidationException("DPoP proof signature is invalid");
        } catch (JOSEException _) {
            throw new DpopValidationException("DPoP proof signature verification failed");
        }
    }

    private JWTClaimsSet extractClaims(SignedJWT proof) {
        try {
            return proof.getJWTClaimsSet();
        } catch (ParseException _) {
            throw new DpopValidationException("Malformed DPoP proof claims");
        }
    }

    private void validateFreshness(Date issueTime) {
        if (issueTime == null) throw new DpopValidationException("DPoP proof missing iat");

        Instant iat = issueTime.toInstant();
        Instant now = Instant.now();

        if (iat.isBefore(now.minus(FRESHNESS_WINDOW)) || iat.isAfter(now.plus(FRESHNESS_WINDOW)))
            throw new DpopValidationException("DPoP proof is not fresh");

    }

    private void validateAth(JWTClaimsSet claims, String opaqueToken) {
        Object ath = claims.getClaim("ath");

        if (!(ath instanceof String athValue) || !computeAth(opaqueToken).equals(athValue))
            throw new DpopValidationException("DPoP proof ath does not match token");
    }

    private String computeAth(String opaqueToken) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(opaqueToken.getBytes(StandardCharsets.UTF_8));

            return Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
