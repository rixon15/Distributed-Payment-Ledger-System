package org.example.authorizationservice.core.config;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.core.oidc.endpoint.OidcParameterNames;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.oauth2.server.authorization.token.JwtEncodingContext;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenCustomizer;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.util.UUID;


@Configuration
public class AuthorizationServerCryptoConfig {

    //FIXME: Currently we generate the keys locally for dev purposes but later on load keys from keystore/HSM/KMS


    @Bean
    public JWKSource<SecurityContext> jwkSource() {
        RSAKey rsaJwk = generateRsaJwk(); // 3072-bit recommended for PSS
        JWKSet jwkSet = new JWKSet(rsaJwk);

        return (selector, _) -> selector.select(jwkSet);
    }

    @Bean
    public OAuth2TokenCustomizer<JwtEncodingContext> jwtCustomizer() {
        return context -> {
            if (OAuth2TokenType.ACCESS_TOKEN.equals(context.getTokenType())) {
                context.getJwsHeader().algorithm(SignatureAlgorithm.PS256);
            }

            if (OidcParameterNames.ID_TOKEN.equals(context.getTokenType().getValue())) {
                context.getJwsHeader().algorithm(SignatureAlgorithm.PS256);
            }
        };
    }

    private RSAKey generateRsaJwk() {
        try {
            KeyPairGenerator keyPairGenerator = KeyPairGenerator.getInstance("RSA");
            keyPairGenerator.initialize(3072);
            KeyPair keyPair = keyPairGenerator.generateKeyPair();

            RSAPublicKey publicKey = (RSAPublicKey) keyPair.getPublic();
            RSAPrivateKey privateKey = (RSAPrivateKey) keyPair.getPrivate();


            return new RSAKey.Builder(publicKey)
                    .privateKey(privateKey)
                    .keyID(UUID.randomUUID().toString())
                    .algorithm(JWSAlgorithm.PS256)
                    .keyUse(KeyUse.SIGNATURE)
                    .build();

        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("Unable to generate RSA key", e);
        }
    }
}
