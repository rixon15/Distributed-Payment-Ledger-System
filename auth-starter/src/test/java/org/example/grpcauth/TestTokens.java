package org.example.grpcauth;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.time.Instant;
import java.util.*;

/**
 * Signing keys, claims and properties for tests, matching what the authorization server issues.
 */
final class TestTokens {

    static final String ISSUER = "http://localhost:9000";
    static final String AUDIENCE = "ledger-service";
    static final String SUBJECT = "7b0c4f3e-1d2a-4c5b-9e8f-0a1b2c3d4e5f";
    static final JOSEObjectType AT_JWT = new JOSEObjectType("at+jwt");

    private TestTokens(){}

    static RSAKey rsaKey(String keyId) {
        try {
            return new RSAKeyGenerator(2048).keyID(keyId).generate();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Claims of a valid internal token issued at {@code now}, living 60 seconds.
     */
    static JWTClaimsSet.Builder claims(Instant now) {
        return new JWTClaimsSet.Builder()
                .issuer(ISSUER)
                .audience(AUDIENCE)
                .subject(SUBJECT)
                .jwtID(UUID.randomUUID().toString())
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plusSeconds(60)))
                .claim("scope", List.of("ledger:read"));
    }

    static JWSHeader.Builder header(RSAKey key) {
        return new JWSHeader.Builder(JWSAlgorithm.PS256).type(AT_JWT).keyID(key.getKeyID());
    }

    static String sign(RSAKey key, JWTClaimsSet claims) {
        return sign(key, header(key).build(), claims);
    }

    static String sign(RSAKey key, JWSHeader header, JWTClaimsSet claims) {
        try {
            SignedJWT jwt = new SignedJWT(header, claims);
            jwt.sign(new RSASSASigner(key));
            return jwt.serialize();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }

    static GrpcJwtAuthProperties properties(Map<String, String> overrides) {
        Map<String, String> props = new HashMap<>();
        props.put("grpc.auth.issuer", ISSUER);
        props.put("grpc.auth.audience", AUDIENCE);
        props.put("grpc.auth.jwks-uri", ISSUER + "/oauth2/jwks");
        props.putAll(overrides);
        return new Binder(new MapConfigurationPropertySource(props))
                .bindOrCreate("grpc.auth", GrpcJwtAuthProperties.class);
    }
}
