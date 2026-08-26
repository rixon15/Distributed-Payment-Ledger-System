package org.example.authorizationservice.core.config;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jwt.SignedJWT;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.jspecify.annotations.Nullable;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.oauth2.server.authorization.OAuth2AuthorizationServerConfigurer;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.security.oauth2.core.endpoint.OAuth2ParameterNames;
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AuthorizationCodeAuthenticationProvider;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AuthorizationCodeAuthenticationToken;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AuthorizationCodeRequestAuthenticationException;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AuthorizationCodeRequestAuthenticationToken;
import org.springframework.security.oauth2.server.authorization.context.AuthorizationServerContextHolder;
import org.springframework.security.oauth2.server.authorization.web.authentication.OAuth2AuthorizationCodeRequestAuthenticationConverter;
import org.springframework.security.oauth2.server.authorization.web.authentication.OAuth2AuthorizationConsentAuthenticationConverter;
import org.springframework.security.web.DefaultRedirectStrategy;
import org.springframework.security.web.RedirectStrategy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.*;
import org.springframework.security.web.util.matcher.MediaTypeRequestMatcher;
import org.springframework.util.StringUtils;
import org.springframework.web.util.UriComponentsBuilder;
import org.springframework.web.util.UriUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.text.ParseException;
import java.util.List;

@Configuration
@EnableWebSecurity
public class AuthorizationServerConfig {

    private static final String REQUIRE_PAR_METADATA = "require_pushed_authorization_requests";

    @Bean
    @Order(1)
    public SecurityFilterChain authorizationServerSecurityFilterChain(HttpSecurity http,
                                                                      OAuth2AuthorizationService authorizationService) {
        OAuth2AuthorizationServerConfigurer authorizationServerConfigurer = new OAuth2AuthorizationServerConfigurer();
        var endpointsMatcher = authorizationServerConfigurer.getEndpointsMatcher();

        authorizationServerConfigurer
                .authorizationEndpoint(authorizationEndpoint ->
                        authorizationEndpoint.authorizationRequestConverters(converters ->
                                        converters.addFirst(new RequireParAuthenticationConverter()))
                                .authorizationResponseHandler(new Rfc9207AuthorizationResponseHandler())
                                .errorResponseHandler(new Rfc9207ErrorResponseHandler()))
                .tokenEndpoint(tokenEndpoint ->
                        tokenEndpoint.accessTokenRequestConverters(converters ->
                                        converters.addFirst(new RequireDPoPAuthenticationConverter()))
                                .authenticationProviders(providers -> providers.replaceAll(provider ->
                                        provider instanceof OAuth2AuthorizationCodeAuthenticationProvider
                                                ? new DPoPAuthorizationCodeBingindAuthenticationProvider(authorizationService, provider)
                                                : provider)))
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

    static final class RequireDPoPAuthenticationConverter implements AuthenticationConverter {


        @Override
        public @Nullable Authentication convert(HttpServletRequest request) {
            String dPoPProof = request.getHeader(OAuth2AccessToken.TokenType.DPOP.getValue());

            if (!StringUtils.hasText(dPoPProof)) {
                OAuth2Error error = new OAuth2Error(
                        "invalid_dpop_proof",
                        "A DPoP proof is required to obtain a sender-constrained access token.",
                        "https://datatracker.ietf.org/doc/html/rfc9449"
                );

                throw new OAuth2AuthenticationException(error);
            }

            return null;
        }
    }

    static final class DPoPAuthorizationCodeBingindAuthenticationProvider implements AuthenticationProvider {

        private static final String DPOP_JKT_PARAMETER_NAME = "dpop_jkt";
        private static final String DPOP_PROOF_PARAMETER_NAME = "dpop_proof";

        private final OAuth2AuthorizationService authorizationService;
        private final AuthenticationProvider delegate;

        public DPoPAuthorizationCodeBingindAuthenticationProvider(OAuth2AuthorizationService authorizationService,
                                                                  AuthenticationProvider delegate) {
            this.authorizationService = authorizationService;
            this.delegate = delegate;
        }

        @Override
        public @Nullable Authentication authenticate(Authentication authentication) throws AuthenticationException {
            OAuth2AuthorizationCodeAuthenticationToken authorizationCodeAuthentication =
                    (OAuth2AuthorizationCodeAuthenticationToken) authentication;

            String dPoPProofCompact = (String) authorizationCodeAuthentication.getAdditionalParameters()
                    .get(DPOP_PROOF_PARAMETER_NAME);

            if (StringUtils.hasText(dPoPProofCompact)) {
                OAuth2Authorization authorization = this.authorizationService.findByToken(
                        authorizationCodeAuthentication.getCode(), new OAuth2TokenType(OAuth2ParameterNames.CODE));

                OAuth2AuthorizationRequest authorizationRequest = authorization != null
                        ? authorization.getAttribute(OAuth2AuthorizationRequest.class.getName())
                        : null;

                String boundDPoPJkt = authorizationRequest != null
                        ? (String) authorizationRequest.getAdditionalParameters().get(DPOP_JKT_PARAMETER_NAME)
                        : null;

                if (StringUtils.hasText(boundDPoPJkt)
                        && !boundDPoPJkt.equals(computeDPoPProofJwkThumbprint(dPoPProofCompact))) {
                    OAuth2Error error = new OAuth2Error(
                            OAuth2ErrorCodes.INVALID_GRANT,
                            "The DPoP proof key does not match the dpop_jkt bound to this authorization code.",
                            "https://datatracker.ietf.org/doc/html/rfc9449#section-10.1"
                    );

                    throw new OAuth2AuthenticationException(error);
                }
            }

            return this.delegate.authenticate(authentication);
        }

        @Override
        public boolean supports(Class<?> authentication) {
            return this.delegate.supports(authentication);
        }

        private static @Nullable String computeDPoPProofJwkThumbprint(String dPoPProofCompact) {
            try {
                JWK jwk = SignedJWT.parse(dPoPProofCompact).getHeader().getJWK();

                return jwk != null ? jwk.computeThumbprint().toString() : null;
            } catch (ParseException | JOSEException e) {
                return null;
            }
        }
    }

    private static final String ISS_PARAMETER_NAME = "iss";

    private static RedirectStrategy seeOtherRedirectStrategy() {
        DefaultRedirectStrategy redirectStrategy = new DefaultRedirectStrategy();
        redirectStrategy.setStatusCode(HttpStatus.SEE_OTHER);
        return redirectStrategy;
    }

    static final class Rfc9207AuthorizationResponseHandler implements AuthenticationSuccessHandler {

        private final RedirectStrategy redirectStrategy = seeOtherRedirectStrategy();

        @Override
        public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response,
                                            Authentication authentication) throws IOException {
            OAuth2AuthorizationCodeRequestAuthenticationToken authorizationCodeRequestAuthentication =
                    (OAuth2AuthorizationCodeRequestAuthenticationToken) authentication;

            UriComponentsBuilder uriBuilder = UriComponentsBuilder
                    .fromUriString(authorizationCodeRequestAuthentication.getRedirectUri())
                    .queryParam(OAuth2ParameterNames.CODE,
                            authorizationCodeRequestAuthentication.getAuthorizationCode().getTokenValue())
                    .queryParam(ISS_PARAMETER_NAME, UriUtils.encode(
                            AuthorizationServerContextHolder.getContext().getIssuer(), StandardCharsets.UTF_8));

            if (StringUtils.hasText(authorizationCodeRequestAuthentication.getState())) {
                uriBuilder.queryParam(OAuth2ParameterNames.STATE,
                        UriUtils.encode(authorizationCodeRequestAuthentication.getState(), StandardCharsets.UTF_8));
            }

            redirectStrategy.sendRedirect(request, response, uriBuilder.build(true).toUriString());
        }
    }

    static final class Rfc9207ErrorResponseHandler implements AuthenticationFailureHandler {

        private final RedirectStrategy redirectStrategy = seeOtherRedirectStrategy();

        @Override
        public void onAuthenticationFailure(HttpServletRequest request, HttpServletResponse response,
                                            AuthenticationException exception) throws IOException {
            OAuth2AuthorizationCodeRequestAuthenticationException authorizationCodeRequestAuthenticationException =
                    (OAuth2AuthorizationCodeRequestAuthenticationException) exception;
            OAuth2Error error = authorizationCodeRequestAuthenticationException.getError();
            OAuth2AuthorizationCodeRequestAuthenticationToken authorizationCodeRequestAuthentication =
                    authorizationCodeRequestAuthenticationException.getAuthorizationCodeRequestAuthentication();

            if (authorizationCodeRequestAuthentication == null
                    || !StringUtils.hasText(authorizationCodeRequestAuthentication.getRedirectUri())) {
                response.sendError(HttpStatus.BAD_REQUEST.value(), error.toString());
                return;
            }

            UriComponentsBuilder uriBuilder = UriComponentsBuilder
                    .fromUriString(authorizationCodeRequestAuthentication.getRedirectUri())
                    .queryParam(OAuth2ParameterNames.ERROR, error.getErrorCode())
                    .queryParam(ISS_PARAMETER_NAME, UriUtils.encode(
                            AuthorizationServerContextHolder.getContext().getIssuer(), StandardCharsets.UTF_8));

            if (StringUtils.hasText(error.getDescription())) {
                uriBuilder.queryParam(OAuth2ParameterNames.ERROR_DESCRIPTION,
                        UriUtils.encode(error.getDescription(), StandardCharsets.UTF_8));
            }
            if (StringUtils.hasText(error.getUri())) {
                uriBuilder.queryParam(OAuth2ParameterNames.ERROR_URI,
                        UriUtils.encode(error.getUri(), StandardCharsets.UTF_8));
            }
            if (StringUtils.hasText(authorizationCodeRequestAuthentication.getState())) {
                uriBuilder.queryParam(OAuth2ParameterNames.STATE,
                        UriUtils.encode(authorizationCodeRequestAuthentication.getState(), StandardCharsets.UTF_8));
            }

            redirectStrategy.sendRedirect(request, response, uriBuilder.build(true).toUriString());
        }

    }

}
