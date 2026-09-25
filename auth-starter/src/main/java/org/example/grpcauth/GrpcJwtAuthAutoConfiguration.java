package org.example.grpcauth;

import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import io.grpc.ServerInterceptor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.core.annotation.Order;
import org.springframework.grpc.server.GlobalServerInterceptor;
import org.springframework.grpc.server.exception.GrpcExceptionHandler;


/**
 * Authenticates every incoming gRPC call with an internal JWT, unless 'grpc.auth.enabled' is false.
 *
 * <p>Every bean backs off when the application defines its own of the same type.
 *
 * <p>Runs before Spring gRPC's exception handler autoconfiguration, whose {@code @ConditionalOnBean} only
 * registers {@code GrpcExceptionHandlerInterceptor} if a {@link GrpcExceptionHandler} bean already exists. Without
 * that interceptor, {@code requireScope} and {@code requirePrincipal} failures reach the caller as {@code UNKNOWN}.
 * Setting 'spring.grpc.server.exception-handler.enabled' to false has the same effect.
 */
@AutoConfiguration(beforeName = "org.springframework.boot.gprc.server.autoconfigure.exception"
+ ".GrpcExceptionHandlerAutoConfiguration")
@ConditionalOnClass({ServerInterceptor.class, GlobalServerInterceptor.class, JWKSource.class})
@ConditionalOnBooleanProperty(name = "grpc.auth.enabled", matchIfMissing = true)
@EnableConfigurationProperties(GrpcJwtAuthProperties.class)
public class GrpcJwtAuthAutoConfiguration {

    /**
     * Position of {@link JwtAuthServerInterceptor} among global server interceptors; lower runs first (outermost).
     *
     * <p>Spring gRPC's exception handler interceptor ({@code HIGHEST_PRECEDENCE}) and observation interceptor (0) run
     * before it, so rejected calls still get status translation, metrics and traces. Application interceptors with
     * the default order run after it and can read {@link GrpcAuthContext}.
     */
    public static final int INTERCEPTOR_ORDER = 100;

    /**
     * Not a default candidate, so it neither replaces nor competes with a {@code JWKSource} the application has for
     * other purposes (an authorization server's own signing keys, say); to use a different source, define a
     * {@link JwtTokenVerifier} bean. It owns non-daemon refresh threads that would otherwise keep the JVM alive; the
     * inferred destroy method closes it with the context, since the built source is {@link java.io.Closeable}.
     */
    @Bean(defaultCandidate = false)
    @ConditionalOnMissingBean(JwtTokenVerifier.class)
    JWKSource<SecurityContext> grpcAuthJwkSource(GrpcJwtAuthProperties properties) {
        return JwksSourceFactory.create(properties.jwksUri(), properties.jwks());
    }

    @Bean
    @ConditionalOnMissingBean
    JwtTokenVerifier grpcAuthTokenVerifier(GrpcJwtAuthProperties properties,
                                           @Qualifier("grpcAuthJwkSource") JWKSource<SecurityContext> jwkSource) {

        return new JwtTokenVerifier(properties, jwkSource);
    }

    @Bean
    @GlobalServerInterceptor
    @Order(INTERCEPTOR_ORDER)
    @ConditionalOnMissingBean
    JwtAuthServerInterceptor grpcAuthServerInterceptor(JwtTokenVerifier verifier, GrpcJwtAuthProperties properties) {
        return new JwtAuthServerInterceptor(verifier, properties.publicMethods());
    }

    @Bean
    @ConditionalOnMissingBean
    GrpcAuthExceptionHandler grpcAuthExceptionHandler() {
        return new GrpcAuthExceptionHandler();
    }
}
