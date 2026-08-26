package org.example.authorizationservice.core.config;

import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import org.example.authorizationservice.service.SigningKeyLifecycleService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.core.oidc.endpoint.OidcParameterNames;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
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

}
