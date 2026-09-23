package org.example.gatewayservice.core.error;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.EnumSet;
import java.util.Set;

/**
 * Translates downstream gRPC failures into RFC 9457 problem responses.
 *
 * <p>Downstream status descriptions are internal by default. They are only forwarded for validation-type codes,
 * which internal services must therefore keep client-safe.
 */
@Slf4j
@RestControllerAdvice
public class GatewayExceptionHandler {

    private static final Set<Status.Code> CLIENT_SAFE_DESCRIPTION_CODES = EnumSet.of(
            Status.Code.INVALID_ARGUMENT, Status.Code.FAILED_PRECONDITION, Status.Code.OUT_OF_RANGE);


    @ExceptionHandler(StatusRuntimeException.class)
    public ProblemDetail handleDownstreamStatus(StatusRuntimeException e) {
        Status status = e.getStatus();
        HttpStatusCode httpStatus = GrpcStatusHttpMapper.toHttpStatus(status.getCode());

        if (httpStatus.is5xxServerError()) log.warn("Downstream gRPC call failed with {}", status, e);

        ProblemDetail problem = ProblemDetail.forStatus(httpStatus);

        if (CLIENT_SAFE_DESCRIPTION_CODES.contains(status.getCode()) && status.getDescription() != null)
            problem.setDetail(status.getDescription());

        return problem;
    }

}
