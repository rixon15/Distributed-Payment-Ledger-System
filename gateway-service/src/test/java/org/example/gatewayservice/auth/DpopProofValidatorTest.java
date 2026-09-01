package org.example.gatewayservice.auth;

import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.gen.ECKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.example.gatewayservice.auth.exception.DpopValidationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Date;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;


class DpopProofValidatorTest {

    private static final String OPAQUE_TOKEN = "opaque-test-token";
    private static final String HTTP_METHOD = "GET";
    private static final String HTTP_URI = "http://gateway.internal/balance";

    private ECKey ecJwk;
    private DpopProofValidator validator;

    @BeforeEach
    void setUp() throws Exception {
        ecJwk = new ECKeyGenerator(Curve.P_256).generate();
        validator = new DpopProofValidator();
    }

    @Test
    void validate_succeeds_forWellFormedProof() throws Exception {
        String proof = buildProof(ecJwk, HTTP_METHOD, HTTP_URI, Instant.now(), OPAQUE_TOKEN, UUID.randomUUID().toString());

        String jti = validator.validate(new DpopValidationRequest(
                proof, ecJwk.toPublicJWK().computeThumbprint().toString(), HTTP_METHOD, HTTP_URI, OPAQUE_TOKEN));

        assertThat(jti).isNotBlank();
    }

    @Test
    void validate_rejects_whenJktDoesNotMatch() throws Exception {
        String proof = buildProof(ecJwk, HTTP_METHOD, HTTP_URI, Instant.now(), OPAQUE_TOKEN, UUID.randomUUID().toString());

        assertThatThrownBy(() -> validator.validate(new DpopValidationRequest(
                proof, "wrong-thumbprint", HTTP_METHOD, HTTP_URI, OPAQUE_TOKEN)))
                .isInstanceOf(DpopValidationException.class);
    }

    @Test
    void validate_rejects_tamperedSignature() throws Exception {
        String proof = buildProof(ecJwk, HTTP_METHOD, HTTP_URI, Instant.now(), OPAQUE_TOKEN, UUID.randomUUID().toString());
        String tampered = proof.substring(0, proof.length() - 4) + "abcd";

        assertThatThrownBy(() -> validator.validate(new DpopValidationRequest(
                tampered, ecJwk.toPublicJWK().computeThumbprint().toString(), HTTP_METHOD, HTTP_URI, OPAQUE_TOKEN)))
                .isInstanceOf(DpopValidationException.class);
    }

    @Test
    void validate_rejects_htmMismatch() throws Exception {
        String proof = buildProof(ecJwk, HTTP_METHOD, HTTP_URI, Instant.now(), OPAQUE_TOKEN, UUID.randomUUID().toString());

        assertThatThrownBy(() -> validator.validate(new DpopValidationRequest(
                proof, ecJwk.toPublicJWK().computeThumbprint().toString(), "POST", HTTP_URI, OPAQUE_TOKEN)))
                .isInstanceOf(DpopValidationException.class);
    }

    @Test
    void validate_rejects_staleProof() throws Exception {
        String proof = buildProof(ecJwk, HTTP_METHOD, HTTP_URI, Instant.now().minusSeconds(300), OPAQUE_TOKEN,
                UUID.randomUUID().toString());

        assertThatThrownBy(() -> validator.validate(new DpopValidationRequest(
                proof, ecJwk.toPublicJWK().computeThumbprint().toString(), HTTP_METHOD, HTTP_URI, OPAQUE_TOKEN)))
                .isInstanceOf(DpopValidationException.class);
    }

    @Test
    void validate_rejects_athMismatch() throws Exception {
        String proof = buildProof(ecJwk, HTTP_METHOD, HTTP_URI, Instant.now(), "different-opaque-token", UUID.randomUUID().toString());

        assertThatThrownBy(() -> validator.validate(new DpopValidationRequest(
                proof, ecJwk.toPublicJWK().computeThumbprint().toString(), HTTP_METHOD, HTTP_URI, OPAQUE_TOKEN)))
                .isInstanceOf(DpopValidationException.class);
    }

    private static String buildProof(ECKey jwk, String htm, String htu,
                                     Instant iat, String opaqueToken, String jti) throws Exception {

        JWSHeader header = new JWSHeader.Builder(JWSAlgorithm.ES256)
                .type(new JOSEObjectType("dpop+jwt"))
                .jwk(jwk.toPublicJWK())
                .build();

        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .claim("htm", htm)
                .claim("htu", htu)
                .issueTime(Date.from(iat))
                .jwtID(jti)
                .claim("ath", computeAth(opaqueToken))
                .build();

        SignedJWT signedJWT = new SignedJWT(header, claims);
        signedJWT.sign(new ECDSASigner(jwk));

        return signedJWT.serialize();
    }

    private static String computeAth(String opaqueToken) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] hash = digest.digest(opaqueToken.getBytes(StandardCharsets.UTF_8));

        return Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
    }
}
