package org.example.gatewayservice.core.error;

import io.grpc.Status;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;

/**
 * Canonical gRPC → HTTP status mapping, as defined in {@code google/rpc/code.proto}.
 */
public final class GrpcStatusHttpMapper {

    /** Non-standard but conventional (nginx, grpc-gateway) status for a request the client abandoned. */
    private static final HttpStatusCode CLIENT_CLOSED_REQUEST = HttpStatusCode.valueOf(499);

    private GrpcStatusHttpMapper(){}

    public static HttpStatusCode toHttpStatus(Status.Code code) {
        return switch (code) {
            case OK -> HttpStatus.OK;
            case INVALID_ARGUMENT, FAILED_PRECONDITION, OUT_OF_RANGE -> HttpStatus.BAD_REQUEST;
            case UNAUTHENTICATED -> HttpStatus.UNAUTHORIZED;
            case PERMISSION_DENIED -> HttpStatus.FORBIDDEN;
            case NOT_FOUND -> HttpStatus.NOT_FOUND;
            case ALREADY_EXISTS, ABORTED -> HttpStatus.CONFLICT;
            case RESOURCE_EXHAUSTED -> HttpStatus.TOO_MANY_REQUESTS;
            case CANCELLED -> CLIENT_CLOSED_REQUEST;
            case UNIMPLEMENTED -> HttpStatus.NOT_IMPLEMENTED;
            case UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE;
            case DEADLINE_EXCEEDED -> HttpStatus.GATEWAY_TIMEOUT;
            case UNKNOWN, INTERNAL, DATA_LOSS -> HttpStatus.INTERNAL_SERVER_ERROR;
        };
    }

}
