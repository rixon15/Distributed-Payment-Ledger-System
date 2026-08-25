package org.example.authorizationservice.core.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.settings.ClientSettings;
import org.springframework.security.oauth2.server.authorization.settings.OAuth2TokenFormat;
import org.springframework.security.oauth2.server.authorization.settings.TokenSettings;

import java.time.Duration;
import java.util.UUID;

@Configuration
public class AuthorizationServerClientBootstrapConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }

    @Bean
    public ApplicationRunner registeredClientSeeder(
            RegisteredClientRepository registeredClientRepository,
            PasswordEncoder passwordEncoder,
            @Value("${demo-client.jwk-set-url}") String demoClientJwkSetUrl
    ) {
        return _ -> {
            final String clientId = "demo-client";

            if (registeredClientRepository.findByClientId(clientId) != null) {
                return;
            }

            /* FIXME: placeholder URL — once the gateway service exists it will be the one
                authenticating here via private_key_jwt and hosting the real JWKS endpoint.
                Until then this client is only reachable from tests that stand up their own\
                local JWKS server. */

            RegisteredClient registeredClient = RegisteredClient.withId(UUID.randomUUID().toString())
                    .clientId(clientId)
                    .clientAuthenticationMethod(ClientAuthenticationMethod.PRIVATE_KEY_JWT)
                    .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                    .authorizationGrantType(AuthorizationGrantType.REFRESH_TOKEN)
                    .redirectUri("http://127.0.0.1:8082/login/oauth2/code/demo-client")
                    .scope("openid")
                    .scope("profile")
                    .clientSettings(ClientSettings.builder()
                            .requireProofKey(true)
                            .requireAuthorizationConsent(false)
                            .jwkSetUrl(demoClientJwkSetUrl)
                            .tokenEndpointAuthenticationSigningAlgorithm(SignatureAlgorithm.PS256)
                            .build())
                    .tokenSettings(TokenSettings.builder()
                            .accessTokenFormat(OAuth2TokenFormat.SELF_CONTAINED)
                            .accessTokenTimeToLive(Duration.ofMinutes(5))
                            .authorizationCodeTimeToLive(Duration.ofSeconds(60))
                            .refreshTokenTimeToLive(Duration.ofDays(30))
                            .reuseRefreshTokens(true)
                            .idTokenSignatureAlgorithm(SignatureAlgorithm.PS256)
                            .build())
                    .build();

            registeredClientRepository.save(registeredClient);
        };
    }
}
