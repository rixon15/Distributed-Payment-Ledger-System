package org.example.grpcauth;


import io.grpc.Context;
import io.grpc.Status;

import java.util.Optional;

/**
 * Access to the caller authenticated by {@link JwtAuthServerInterceptor} for the current gRPC call.
 *
 * <p>The principal lives in the gRPC {@link Context}, not a {@code ThreadLocal}: grpc-java may run the callbacks of
 * one call on different threads and attaches the call's context around each of them. Work handed to another thread
 * only sees the principal if it is wrapped with {@code Context.current().wrap(...)} or run on an executor from
 * {@code Context.currentContextExecutor(...)}.
 */
public final class GrpcAuthContext {

    /**
     * Package-private so that only the interceptor can attach a principal: application code can read the caller, but
     * cannot put one in place of the verified caller.
     */
    static final Context.Key<AuthenticatedPrincipal> PRINCIPAL = Context.key("grpc-auth-principal");

    private GrpcAuthContext(){}

    /**
     * @return the caller of the current call, or empty outside a call or in a public method
     */
    public static Optional<AuthenticatedPrincipal> current() {
        return Optional.ofNullable(PRINCIPAL.get());
    }

    /**
     * @return the caller of the current call
     * @throws io.grpc.StatusRuntimeException with {@code UNAUTHENTICATED} if there is none, e.g. when called from a
     * method configured as public
     */
    public static AuthenticatedPrincipal requirePrincipal() {
        return current().orElseThrow(() -> Status.UNAUTHENTICATED
                .withDescription("Invalid or missing credentials")
                .asRuntimeException());
    }
}
