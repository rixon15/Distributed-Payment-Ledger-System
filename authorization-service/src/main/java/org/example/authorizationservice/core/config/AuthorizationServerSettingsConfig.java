package org.example.authorizationservice.core.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.server.authorization.settings.AuthorizationServerSettings;

@Configuration
public class AuthorizationServerSettingsConfig {

    /* The issuer is fixed by configuration, not derived from each request: ExchangeToken mints tokens on a gRPC call,
     * where there is no HTTP request to derive it from*/
    @Bean
    public AuthorizationServerSettings authorizationServerSettings(
            @Value("${authorization-server.issuer}") String issuer) {
        return AuthorizationServerSettings.builder()
                .issuer(issuer)
                .pushedAuthorizationRequestEndpoint("/oauth2/par")
                .build();
    }
}
