package org.example.grpcauth;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.JWKSetBasedJWKSource;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.jwk.source.RefreshAheadCachingJWKSetSource;
import com.nimbusds.jose.proc.SecurityContext;
import io.grpc.*;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.ClientCalls;
import io.grpc.stub.MetadataUtils;
import io.grpc.stub.ServerCalls;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.annotation.ImportCandidates;
import org.springframework.boot.grpc.server.autoconfigure.GrpcServerAutoConfiguration;
import org.springframework.boot.grpc.server.autoconfigure.exception.GrpcExceptionHandlerAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.grpc.server.GlobalServerInterceptor;
import org.springframework.grpc.server.exception.GrpcExceptionHandlerInterceptor;
import org.springframework.grpc.server.service.GrpcServiceConfigurer;
import org.springframework.grpc.server.service.GrpcServiceSpec;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.example.grpcauth.TestGrpc.method;
import static org.example.grpcauth.TestTokens.*;

class GrpcJwtAuthAutoConfigurationTest {

    private static final Instant NOW = Instant.parse("2026-09-24T12:00:00Z");
    private static final RSAKey KEY = TestTokens.rsaKey("key-1");
    private static final MethodDescriptor<String, String> WRITE = method("test.Ledger/Write");

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(GrpcJwtAuthAutoConfiguration.class))
            .withPropertyValues(
                    "grpc.auth.issuer=" + ISSUER,
                    "grpc.auth.audience=" + AUDIENCE,
                    "grpc.auth.jwks-uri=" + ISSUER + "/oauth2/jwks");

    @Test
    void isListedForAutoConfiguration() {
        assertThat(ImportCandidates.load(AutoConfiguration.class, getClass().getClassLoader()))
                .contains(GrpcJwtAuthAutoConfiguration.class.getName());
    }

    @Test
    void registersAuthenticationBeans() {
        runner.run(context -> {
            assertThat(context).hasSingleBean(GrpcJwtAuthProperties.class);
            assertThat(context).hasSingleBean(JwtTokenVerifier.class);
            assertThat(context).hasSingleBean(JwtAuthServerInterceptor.class);
            assertThat(context).hasSingleBean(GrpcAuthExceptionHandler.class);
            assertThat(context).hasBean("grpcAuthJwkSource");
            assertThat(context.getBeansWithAnnotation(GlobalServerInterceptor.class))
                    .containsValue(context.getBean(JwtAuthServerInterceptor.class));
        });
    }

    @Test
    void backsOffForApplicationBeans() {
        runner.withUserConfiguration(ApplicationAuthConfig.class).run(context -> {
            assertThat(context.getBean(JwtTokenVerifier.class))
                    .isSameAs(context.getBean(ApplicationAuthConfig.class).verifier);
            assertThat(context).doesNotHaveBean("grpcAuthJwkSource");
            assertThat(context).getBean(JwtAuthServerInterceptor.class).isInstanceOf(CustomInterceptor.class);
            assertThat(context).getBean(GrpcAuthExceptionHandler.class).isInstanceOf(CustomExceptionHandler.class);
        });
    }

    @Test
    void neitherReplacesNorCompetesWithApplicationJwkSource() {
        runner.withUserConfiguration(ApplicationJwkSourceConfig.class).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasBean("grpcAuthJwkSource");
            assertThat(context.getBean(SigningKeyUser.class).keys()).isSameAs(context.getBean("signingKeys"));
        });
    }

    @Test
    void closesJwkSourceWithContext() {
        AtomicReference<JWKSource<?>> source = new AtomicReference<>();

        runner.run(context -> source.set(context.getBean("grpcAuthJwkSource", JWKSource.class)));

        var cache = (RefreshAheadCachingJWKSetSource<?>) ((JWKSetBasedJWKSource<?>) source.get()).getJWKSetSource();
        assertThat(cache.getExecutorService().isShutdown()).isTrue();
    }

    @Test
    void registersNothingWhenDisabled() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(GrpcJwtAuthAutoConfiguration.class))
                .withPropertyValues("grpc.auth.enabled=false")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(GrpcJwtAuthProperties.class);
                    assertThat(context).doesNotHaveBean(JwtAuthServerInterceptor.class);
                    assertThat(context).doesNotHaveBean(GrpcAuthExceptionHandler.class);
                });
    }

    @Test
    void failsStartupWithoutRequiredProperties() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(GrpcJwtAuthAutoConfiguration.class))
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).rootCause()
                            .hasMessageContaining("grpc.auth.issuer must be set");
                });
    }

    /**
     * Builds the service the way a Spring gRPC server does, through {@link GrpcServiceConfigurer}, so the global
     * interceptor order and the conditional exception handler registration are Spring gRPC's own.
     */
    @Test
    void runsInsideSpringGrpcExceptionHandlingAndBeforeApplicationInterceptors() {
        runner.withConfiguration(AutoConfigurations.of(
                        GrpcServerAutoConfiguration.class, GrpcExceptionHandlerAutoConfiguration.class))
                .withUserConfiguration(GrpcServerConfig.class)
                .run(context -> {
                    assertThat(context.getBeanProvider(ServerInterceptor.class).orderedStream().toList())
                            .hasExactlyElementsOfTypes(GrpcExceptionHandlerInterceptor.class,
                                    JwtAuthServerInterceptor.class, PrincipalRecorder.class);

                    ServerServiceDefinition ledger = context.getBean(GrpcServiceConfigurer.class)
                            .configure(new GrpcServiceSpec(context.getBean(BindableService.class), null), null);
                    String name = InProcessServerBuilder.generateName();
                    Server server = InProcessServerBuilder.forName(name).addService(ledger).build().start();
                    ManagedChannel channel = InProcessChannelBuilder.forName(name).build();

                    try {
                        assertThatThrownBy(() -> write(channel, sign(KEY, claims(NOW).build())))
                                .isInstanceOfSatisfying(StatusRuntimeException.class, e -> assertThat(
                                        e.getStatus().getCode()).isEqualTo(Status.Code.PERMISSION_DENIED));
                        assertThat(context.getBean(PrincipalRecorder.class).subject).hasValue(SUBJECT);
                    } finally {
                        channel.shutdownNow();
                        server.shutdownNow();
                    }
                });
    }

    private static String write(Channel channel, String token) {
        Metadata headers = new Metadata();
        headers.put(JwtAuthServerInterceptor.AUTHORIZATION, "Bearer " + token);
        Channel intercepted = ClientInterceptors.intercept(channel, MetadataUtils.newAttachHeadersInterceptor(headers));
        return ClientCalls.blockingUnaryCall(intercepted, WRITE, CallOptions.DEFAULT, "request");
    }

    private static JwtTokenVerifier fixedClockVerifier() {
        return new JwtTokenVerifier(TestTokens.properties(Map.of()),
                new ImmutableJWKSet<>(new JWKSet(KEY.toPublicJWK())), Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Configuration(proxyBeanMethods = false)
    static class ApplicationAuthConfig {

        final JwtTokenVerifier verifier = fixedClockVerifier();

        @Bean
        JwtTokenVerifier verifier() {
            return verifier;
        }

        @Bean
        CustomInterceptor interceptor() {
            return new CustomInterceptor(verifier);
        }

        @Bean
        CustomExceptionHandler exceptionHandler() {
            return new CustomExceptionHandler();
        }
    }

    static class CustomInterceptor extends JwtAuthServerInterceptor {
        CustomInterceptor(JwtTokenVerifier verifier) {
            super(verifier, List.of());
        }
    }

    static class CustomExceptionHandler extends GrpcAuthExceptionHandler {
    }

    @Configuration(proxyBeanMethods = false)
    static class ApplicationJwkSourceConfig {

        @Bean
        JWKSource<SecurityContext> signingKeys() {
            return new ImmutableJWKSet<>(new JWKSet(KEY));
        }

        @Bean
        SigningKeyUser signingKeyUser(JWKSource<SecurityContext> keys) {
            return new SigningKeyUser(keys);
        }
    }

    record SigningKeyUser(JWKSource<SecurityContext> keys) {
    }

    @Configuration(proxyBeanMethods = false)
    static class GrpcServerConfig {

        @Bean
        JwtTokenVerifier verifier() {
            return fixedClockVerifier();
        }

        @Bean
        BindableService ledger() {
            return () -> ServerServiceDefinition.builder("test.Ledger")
                    .addMethod(WRITE, ServerCalls.asyncUnaryCall((request, observer) -> {
                        GrpcAuthContext.requirePrincipal().requireScope("ledger:write");
                        observer.onNext("written");
                        observer.onCompleted();
                    }))
                    .build();
        }

        @Bean
        @GlobalServerInterceptor
        PrincipalRecorder principalRecorder() {
            return new PrincipalRecorder();
        }
    }

    /**
     * An application interceptor with the default order, which should see the authenticated caller.
     */
    static class PrincipalRecorder implements ServerInterceptor {

        final AtomicReference<String> subject = new AtomicReference<>();

        @Override
        public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
                ServerCall<ReqT, RespT> call, Metadata headers, ServerCallHandler<ReqT, RespT> next) {
            GrpcAuthContext.current().ifPresent(principal -> subject.set(principal.subject()));
            return next.startCall(call, headers);
        }
    }
}
