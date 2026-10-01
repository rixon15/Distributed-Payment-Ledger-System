package org.example.authorizationservice.core.config;

import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import org.example.authorizationservice.service.SigningKeyLifecycleService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.core.oidc.endpoint.OidcParameterNames;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.oauth2.server.authorization.token.JwtEncodingContext;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenCustomizer;

@Configuration
public class AuthorizationServerCryptoConfig {

    @Bean
    public JWKSource<SecurityContext> jwkSource(SigningKeyLifecycleService signingKeyLifecycleService) {
        return (selector, _) -> selector.select(signingKeyLifecycleService.jwkSet());
    }

    @Bean
    public JwtEncoder jwtEncoder(JWKSource<SecurityContext> jwkSource) {
        return new NimbusJwtEncoder(jwkSource);
    }

    @Bean
    public OAuth2TokenCustomizer<JwtEncodingContext> jwtCustomizer() {
        return context -> {
            /* Only reached for a client registered with SELF_CONTAINED access tokens. Clients must only ever hold
               opaque tokens: a JWT in a client's hands is signed by the same key as the internal tokens, so it could
               be presented to an internal service directly, bypassing the gateway. */
            if (OAuth2TokenType.ACCESS_TOKEN.equals(context.getTokenType())) {
                throw new OAuth2AuthenticationException(new OAuth2Error(
                        OAuth2ErrorCodes.SERVER_ERROR,
                        "Client '%s' is not configured for reference access token."
                                .formatted(context.getRegisteredClient().getClientId()),
                        null));
            }

            if (OidcParameterNames.ID_TOKEN.equals(context.getTokenType().getValue())) {
                context.getJwsHeader().algorithm(SignatureAlgorithm.PS256);
            }
        };
    }

}
