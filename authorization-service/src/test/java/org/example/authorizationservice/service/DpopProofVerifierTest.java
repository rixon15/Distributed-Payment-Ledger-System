package org.example.authorizationservice.service;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.ECKeyGenerator;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jose.util.Base64URL;
import org.example.authorizationservice.support.DpopProofs;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class DpopProofVerifierTest {

    private static final String TOKEN = "opaque-access-token";

    private static ECKey key;
    private static ECKey otherKey;
    private static String jkt;

    private final DpopProofVerifier verifier = new DpopProofVerifier();

    @BeforeAll
    static void generateKeys() throws Exception {
        key = new ECKeyGenerator(Curve.P_256).generate();
        otherKey = new ECKeyGenerator(Curve.P_256).generate();
        jkt = key.computeThumbprint().toString();
    }

    @Test
    void acceptsProofSignedByTheBoundEcKey() {
        String proof = DpopProofs.signedBy(key).forAccessToken(TOKEN).build();

        assertThat(verifier.isValid(proof, TOKEN, jkt)).isTrue();
    }

    @Test
    void acceptsProofSignedByTheBoundRsaKey() throws Exception {
        RSAKey rsaKey = new RSAKeyGenerator(2048).generate();
        String proof = DpopProofs.signedBy(rsaKey).forAccessToken(TOKEN).build();

        assertThat(verifier.isValid(proof, TOKEN, rsaKey.computeThumbprint().toString())).isTrue();
    }

    @Test
    void rejectsProofSignedByAnotherKey() {
        String proof = DpopProofs.signedBy(otherKey).forAccessToken(TOKEN).build();

        assertThat(verifier.isValid(proof, TOKEN, jkt)).isFalse();
    }

    @Test
    void rejectsProofThatEmbedsTheBoundKeyButIsSignedByAnother() {
        String proof = DpopProofs.signedBy(otherKey).embedding(key.toPublicJWK()).forAccessToken(TOKEN).build();

        assertThat(verifier.isValid(proof, TOKEN, jkt)).isFalse();
    }

    @Test
    void rejectsProofForAnotherToken() {
        String proof = DpopProofs.signedBy(key).forAccessToken("another-token").build();

        assertThat(verifier.isValid(proof, TOKEN, jkt)).isFalse();
    }

    @Test
    void rejectsProofWithoutAccessTokenHash() {
        String proof = DpopProofs.signedBy(key).build();

        assertThat(verifier.isValid(proof, TOKEN, jkt)).isFalse();
    }

    @Test
    void rejectsStaleProof() {
        Instant tooOld = Instant.now().minus(DpopProofVerifier.FRESHNESS_WINDOW).minus(Duration.ofSeconds(5));
        String proof = DpopProofs.signedBy(key).forAccessToken(TOKEN).issuedAt(tooOld).build();

        assertThat(verifier.isValid(proof, TOKEN, jkt)).isFalse();
    }

    @Test
    void rejectsProofIssuedInTheFuture() {
        Instant tooNew = Instant.now().plus(DpopProofVerifier.FRESHNESS_WINDOW).plus(Duration.ofSeconds(5));
        String proof = DpopProofs.signedBy(key).forAccessToken(TOKEN).issuedAt(tooNew).build();

        assertThat(verifier.isValid(proof, TOKEN, jkt)).isFalse();
    }

    @Test
    void rejectsWrongType() {
        String proof = DpopProofs.signedBy(key).forAccessToken(TOKEN).type("jwt").build();

        assertThat(verifier.isValid(proof, TOKEN, jkt)).isFalse();
    }

    @Test
    void rejectsDisallowedAlgorithm() throws Exception {
        RSAKey rsaKey = new RSAKeyGenerator(2048).generate();
        String proof = DpopProofs.signedBy(rsaKey).algorithm(JWSAlgorithm.RS256).forAccessToken(TOKEN).build();

        assertThat(verifier.isValid(proof, TOKEN, rsaKey.computeThumbprint().toString())).isFalse();
    }

    @Test
    void rejectsProofThatEmbedsAPrivateKey() throws Exception {
        // Assembled by hand: Nimbus refuses to build a header around a private key
        String header = "{\"alg\":\"ES256\",\"typ\":\"dpop+jwt\",\"jwk\":" + key.toJSONString() + "}";
        String validProof = DpopProofs.signedBy(key).forAccessToken(TOKEN).build();
        String payload = validProof.split("\\.")[1];
        String signingInput = Base64URL.encode(header) + "." + payload;
        Base64URL signature = new ECDSASigner(key)
                .sign(new JWSHeader(JWSAlgorithm.ES256), signingInput.getBytes(StandardCharsets.US_ASCII));

        assertThat(verifier.isValid(signingInput + "." + signature, TOKEN, jkt)).isFalse();
    }

    @Test
    void rejectsGarbage() {
        assertThat(verifier.isValid("not-a-jwt", TOKEN, jkt)).isFalse();
        assertThat(verifier.isValid("", TOKEN, jkt)).isFalse();
    }
}
