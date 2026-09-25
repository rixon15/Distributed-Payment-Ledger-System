package org.example.grpcauth;

import com.nimbusds.jose.KeySourceException;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import io.grpc.*;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.ClientCalls;
import io.grpc.stub.MetadataUtils;
import io.grpc.stub.ServerCalls;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.grpc.server.exception.GrpcExceptionHandlerInterceptor;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.example.grpcauth.TestGrpc.method;
import static org.example.grpcauth.TestTokens.*;

/**
 * Runs the interceptor in an in-process server behind Spring gRPC's exception handler interceptor, the way the
 * autoconfiguration wires it. Methods come from {@link TestGrpc}, so no generated stubs are needed.
 */
class JwtAuthServerInterceptorTest {

    private static final Instant NOW = Instant.parse("2026-09-24T12:00:00Z");
    private static final RSAKey KEY = TestTokens.rsaKey("key-1");

    private static final MethodDescriptor<String, String> WHOAMI = method("test.Ledger/Whoami");
    private static final MethodDescriptor<String, String> WRITE = method("test.Ledger/Write");
    private static final MethodDescriptor<String, String> OPEN = method("test.Ledger/Open");
    private static final MethodDescriptor<String, String> PING = method("test.Public/Ping");

    private final AtomicInteger handlerCalls = new AtomicInteger();
    private final AtomicReference<Metadata> serviceHeaders = new AtomicReference<>();

    private Server server;
    private ManagedChannel channel;

    @AfterEach
    void tearDown() {
        if (channel != null) channel.shutdownNow();
        if (server != null) server.shutdownNow();
    }

    @Test
    void exposesPrincipalToServiceAndStripsAuthorizationHeader() throws Exception {
        start(new ImmutableJWKSet<>(new JWKSet(KEY.toPublicJWK())));

        String subject = call(WHOAMI, "Bearer " + sign(KEY, claims(NOW).build()));

        assertThat(subject).isEqualTo(SUBJECT);
        assertThat(serviceHeaders.get().containsKey(JwtAuthServerInterceptor.AUTHORIZATION)).isFalse();
    }

    @Test
    void acceptsSchemeInAnyCase() throws Exception {
        start(new ImmutableJWKSet<>(new JWKSet(KEY.toPublicJWK())));

        assertThat(call(WHOAMI, "bEaReR " + sign(KEY, claims(NOW).build()))).isEqualTo(SUBJECT);
    }

    @Test
    void rejectsMissingAuthorizationHeader() throws Exception {
        start(new ImmutableJWKSet<>(new JWKSet(KEY.toPublicJWK())));

        assertStatus(() -> call(WHOAMI), Status.Code.UNAUTHENTICATED, "Invalid or missing credentials");
        assertThat(handlerCalls).hasValue(0);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "Bearer", "Bearer ", "Basic dXNlcjpwYXNz", "Token abc", "Bearer a b", "Bearer a\"b"})
    void rejectsMalformedAuthorizationHeader(String header) throws Exception {
        start(new ImmutableJWKSet<>(new JWKSet(KEY.toPublicJWK())));

        assertStatus(() -> call(WHOAMI, header), Status.Code.UNAUTHENTICATED, "Invalid or missing credentials");
        assertThat(handlerCalls).hasValue(0);
    }

    @Test
    void rejectsMultipleAuthorizationHeaders() throws Exception {
        start(new ImmutableJWKSet<>(new JWKSet(KEY.toPublicJWK())));
        String header = "Bearer " + sign(KEY, claims(NOW).build());

        assertStatus(() -> call(WHOAMI, header, header), Status.Code.UNAUTHENTICATED,
                "Invalid or missing credentials");
        assertThat(handlerCalls).hasValue(0);
    }

    @Test
    void rejectsInvalidTokenWithoutRevealingReason() throws Exception {
        start(new ImmutableJWKSet<>(new JWKSet(KEY.toPublicJWK())));
        String expired = sign(KEY, claims(NOW.minusSeconds(300)).build());

        assertStatus(() -> call(WHOAMI, "Bearer " + expired), Status.Code.UNAUTHENTICATED,
                "Invalid or missing credentials");
        assertThat(handlerCalls).hasValue(0);
    }

    @Test
    void reportsUnavailableWhenSigningKeysCannotBeFetched() throws Exception {
        start((selector, context) -> {
            throw new KeySourceException("Connection refused");
        });

        assertStatus(() -> call(WHOAMI, "Bearer " + sign(KEY, claims(NOW).build())), Status.Code.UNAVAILABLE,
                "Authentication temporarily unavailable");
        assertThat(handlerCalls).hasValue(0);
    }

    @Test
    void letsPublicServiceThroughWithoutPrincipal() throws Exception {
        start(new ImmutableJWKSet<>(new JWKSet(KEY.toPublicJWK())));

        assertThat(call(PING)).isEqualTo("anonymous");
    }

    @Test
    void doesNotAuthenticatePublicMethodEvenWithToken() throws Exception {
        start(new ImmutableJWKSet<>(new JWKSet(KEY.toPublicJWK())));

        assertThat(call(PING, "Bearer " + sign(KEY, claims(NOW).build()))).isEqualTo("anonymous");
    }

    @Test
    void requirePrincipalInPublicMethodIsUnauthenticated() throws Exception {
        start(new ImmutableJWKSet<>(new JWKSet(KEY.toPublicJWK())));

        assertStatus(() -> call(OPEN), Status.Code.UNAUTHENTICATED, "Invalid or missing credentials");
        assertThat(handlerCalls).hasValue(1);
    }

    @Test
    void publicMethodDoesNotOpenRestOfService() throws Exception {
        start(new ImmutableJWKSet<>(new JWKSet(KEY.toPublicJWK())));

        assertStatus(() -> call(WHOAMI), Status.Code.UNAUTHENTICATED, "Invalid or missing credentials");
    }

    @Test
    void missingScopeIsPermissionDenied() throws Exception {
        start(new ImmutableJWKSet<>(new JWKSet(KEY.toPublicJWK())));

        assertStatus(() -> call(WRITE, "Bearer " + sign(KEY, claims(NOW).build())), Status.Code.PERMISSION_DENIED,
                "Insufficient scope");
    }

    @Test
    void grantedScopePasses() throws Exception {
        start(new ImmutableJWKSet<>(new JWKSet(KEY.toPublicJWK())));
        String token = sign(KEY, claims(NOW).claim("scope", List.of("ledger:read", "ledger:write")).build());

        assertThat(call(WRITE, "Bearer " + token)).isEqualTo("written");
    }

    private void start(JWKSource<SecurityContext> keySource) throws IOException {
        GrpcJwtAuthProperties properties = TestTokens.properties(Map.of(
                "grpc.auth.public-methods", "test.Public/*,test.Ledger/Open"));
        JwtTokenVerifier verifier = new JwtTokenVerifier(properties, keySource, Clock.fixed(NOW, ZoneOffset.UTC));

        ServerServiceDefinition ledger = ServerServiceDefinition.builder("test.Ledger")
                .addMethod(WHOAMI, unary(() -> GrpcAuthContext.requirePrincipal().subject()))
                .addMethod(WRITE, unary(() -> {
                    GrpcAuthContext.requirePrincipal().requireScope("ledger:write");
                    return "written";
                }))
                .addMethod(OPEN, unary(() -> GrpcAuthContext.requirePrincipal().subject()))
                .build();
        ServerServiceDefinition open = ServerServiceDefinition.builder("test.Public")
                .addMethod(PING, unary(() -> GrpcAuthContext.current().isPresent() ? "authenticated" : "anonymous"))
                .build();

        String name = InProcessServerBuilder.generateName();
        // The last interceptor runs first: exception handling wraps authentication, which wraps the header capture
        List<ServerInterceptor> interceptors = List.of(
                captureHeaders(),
                new JwtAuthServerInterceptor(verifier, properties.publicMethods()),
                new GrpcExceptionHandlerInterceptor(new GrpcAuthExceptionHandler()));

        server = InProcessServerBuilder.forName(name)
                .addService(ServerInterceptors.intercept(ledger, interceptors))
                .addService(ServerInterceptors.intercept(open, interceptors))
                .build()
                .start();
        channel = InProcessChannelBuilder.forName(name).build();
    }

    private ServerCallHandler<String, String> unary(Supplier<String> body) {
        return ServerCalls.asyncUnaryCall((request, observer) -> {
            handlerCalls.incrementAndGet();
            observer.onNext(body.get());
            observer.onCompleted();
        });
    }

    private ServerInterceptor captureHeaders() {
        return new ServerInterceptor() {
            @Override
            public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
                    ServerCall<ReqT, RespT> call, Metadata headers, ServerCallHandler<ReqT, RespT> next) {
                serviceHeaders.set(headers);
                return next.startCall(call, headers);
            }
        };
    }

    private String call(MethodDescriptor<String, String> method, String... authorization) {
        Metadata headers = new Metadata();
        for (String value : authorization) headers.put(JwtAuthServerInterceptor.AUTHORIZATION, value);

        Channel intercepted = ClientInterceptors.intercept(channel, MetadataUtils.newAttachHeadersInterceptor(headers));
        return ClientCalls.blockingUnaryCall(intercepted, method, CallOptions.DEFAULT, "request");
    }

    private static void assertStatus(Runnable call, Status.Code code, String description) {
        assertThatThrownBy(call::run)
                .isInstanceOfSatisfying(StatusRuntimeException.class, e -> {
                    assertThat(e.getStatus().getCode()).isEqualTo(code);
                    assertThat(e.getStatus().getDescription()).isEqualTo(description);
                });
    }
}
