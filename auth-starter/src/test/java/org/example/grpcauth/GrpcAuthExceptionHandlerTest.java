package org.example.grpcauth;

import io.grpc.Status;
import io.grpc.StatusException;
import org.example.grpcauth.exception.InsufficientScopeException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class GrpcAuthExceptionHandlerTest {

    private final GrpcAuthExceptionHandler handler = new GrpcAuthExceptionHandler();

    @Test
    void mapsInsufficientScopeToPermissionDeniedWithoutNamingScope() {
        StatusException status = handler.handleException(new InsufficientScopeException("ledger:write"));

        assertThat(status).isNotNull();
        assertThat(status.getStatus().getCode()).isEqualTo(Status.Code.PERMISSION_DENIED);
        assertThat(status.getStatus().getDescription()).isEqualTo("Insufficient scope");
    }

    @Test
    void leavesOtherExceptionsToOtherHandlers() {
        assertThat(handler.handleException(new IllegalStateException("boom"))).isNull();
        assertThat(handler.handleException(Status.UNAUTHENTICATED.asRuntimeException())).isNull();
    }

}
