package org.example.authorizationservice.core.config;

import jakarta.servlet.http.HttpServletRequest;
import org.jspecify.annotations.Nullable;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.oauth2.server.authorization.OAuth2AuthorizationServerConfigurer;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.core.endpoint.OAuth2ParameterNames;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AuthorizationCodeRequestAuthenticationException;
import org.springframework.security.oauth2.server.authorization.web.authentication.OAuth2AuthorizationCodeRequestAuthenticationConverter;
import org.springframework.security.oauth2.server.authorization.web.authentication.OAuth2AuthorizationConsentAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AuthenticationConverter;
import org.springframework.security.web.authentication.DelegatingAuthenticationConverter;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;
import org.springframework.security.web.util.matcher.MediaTypeRequestMatcher;
import org.springframework.util.StringUtils;

import java.util.List;

@Configuration
@EnableWebSecurity
public class AuthorizationServerConfig {

    private static final String REQUIRE_PAR_METADATA = "require_pushed_authorization_requests";

    @Bean
    @Order(1)
    public SecurityFilterChain authorizationServerSecurityFilterChain(HttpSecurity http) {
        OAuth2AuthorizationServerConfigurer authorizationServerConfigurer = new OAuth2AuthorizationServerConfigurer();
        var endpointsMatcher = authorizationServerConfigurer.getEndpointsMatcher();

        authorizationServerConfigurer
                .authorizationEndpoint(authorizationEndpoint ->
                        authorizationEndpoint.authorizationRequestConverters(converters ->
                                converters.addFirst(new RequireParAuthenticationConverter())))
                .pushedAuthorizationRequestEndpoint(Customizer.withDefaults())
                .authorizationServerMetadataEndpoint(metadataEndpoint -> metadataEndpoint.authorizationServerMetadataCustomizer(
                        metadata -> metadata.claim(REQUIRE_PAR_METADATA, true)
                ))
                .oidc(oidc -> oidc.providerConfigurationEndpoint(providerConfigurationEndpoint ->
                        providerConfigurationEndpoint.providerConfigurationCustomizer(
                                metadata -> metadata.claim(REQUIRE_PAR_METADATA, true)
                        )));

        http
                .securityMatcher(endpointsMatcher)
                .with(authorizationServerConfigurer, Customizer.withDefaults())
                .authorizeHttpRequests(authorize ->
                        authorize.requestMatchers(
                                        "/oauth2/jwks",
                                        "/.well-known/jwks.json",
                                        "/oauth2/par",
                                        "/.well-known/oauth-authorization-server",
                                        "/.well-known/openid-configuration"
                                ).permitAll()
                                .anyRequest().authenticated())
                .exceptionHandling(exceptions -> exceptions
                        .defaultAuthenticationEntryPointFor(
                                new LoginUrlAuthenticationEntryPoint("/login"),
                                new MediaTypeRequestMatcher(MediaType.TEXT_HTML)
                        ));

        return http.build();
    }

    @Bean
    @Order(2)
    public SecurityFilterChain defaultSecurityFilterChain(HttpSecurity http) {
        http
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers("/login", "/error").permitAll()
                        .anyRequest().authenticated()
                )
                .formLogin(Customizer.withDefaults());

        return http.build();
    }


    static final class RequireParAuthenticationConverter implements AuthenticationConverter {

        private final AuthenticationConverter delegate = new DelegatingAuthenticationConverter(List.of(
                new OAuth2AuthorizationCodeRequestAuthenticationConverter(),
                new OAuth2AuthorizationConsentAuthenticationConverter()
        ));

        @Override
        public @Nullable Authentication convert(HttpServletRequest request) {
            String requestUri = request.getParameter(OAuth2ParameterNames.REQUEST_URI);
            String responseType = request.getParameter(OAuth2ParameterNames.RESPONSE_TYPE);
            String clientId = request.getParameter(OAuth2ParameterNames.CLIENT_ID);

            boolean looksLikeAuthorizationRequest = StringUtils.hasText(clientId) && StringUtils.hasText(responseType);

            if (looksLikeAuthorizationRequest && !StringUtils.hasText(requestUri)) {
                OAuth2Error error = new OAuth2Error(
                        OAuth2ErrorCodes.INVALID_REQUEST,
                        "Pushed Authorization Request is required. Send the authorization request to /oauth2/par first, then call the authorization endpoint with request_uri.",
                        "https://www.rfc-editor.org/rfc/rfc9126#section-5"
                );

                throw new OAuth2AuthorizationCodeRequestAuthenticationException(error, null);
            }

            return this.delegate.convert(request);
        }
    }

}
