package org.example.grpcauth;

import io.grpc.Status;
import io.grpc.StatusException;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.example.grpcauth.exception.InsufficientScopeException;
import org.jspecify.annotations.Nullable;
import org.springframework.grpc.server.exception.GrpcExceptionHandler;

/**
 * Translates authorization failures thrown from service methods into gRPC statuses.
 *
 * <p>Plain grpc-java closes a call whose method threw with {@code UNKNOWN}, whatever the exception.
 * Spring gRPC's {@code GrpcExceptionHandlerInterceptor} fixes that, but it is only registered when a
 * {@link GrpcExceptionHandler} bean exists; this handler is that bean. It maps:
 * <ul>
 *     <li>{@link InsufficientScopeException} to {@code PERMISSION_DENIED}, without naming the scope;</li>
 *     <li>anything else to {@code null}, leaving it to other handlers or Spring gRPC's fallback, which turns the
 *     {@code UNAUTHENTICATED} from {@link GrpcAuthContext#requirePrincipal()} back into its own status.</li>
 * </ul>
 */
public class GrpcAuthExceptionHandler implements GrpcExceptionHandler {

    private static final Log logger = LogFactory.getLog(GrpcAuthExceptionHandler.class);

    @Override
    public @Nullable StatusException handleException(Throwable exception) {
        if (exception instanceof InsufficientScopeException e) {
            logger.warn("Denied call: " + e.getMessage());
            return Status.PERMISSION_DENIED.withDescription("Insufficient scope").asException();
        }

        return null;
    }
}
