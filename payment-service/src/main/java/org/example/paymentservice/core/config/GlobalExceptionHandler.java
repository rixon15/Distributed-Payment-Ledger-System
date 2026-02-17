package org.example.paymentservice.core.config;

import org.example.paymentservice.core.exception.DuplicatedRequestException;
import org.example.paymentservice.core.exception.RiskEngineException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.net.URI;
import java.time.Instant;

@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    @ExceptionHandler(DuplicatedRequestException.class)
    public ProblemDetail handleDuplicatedRequest(DuplicatedRequestException ex) {
        // ProblemDetail is the standard for Spring Boot 3+ (RFC 7807)
        ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
        problemDetail.setTitle("Duplicate Request");
        problemDetail.setType(URI.create("https://api.yourdomain.com/errors/idempotency-conflict"));
        problemDetail.setProperty("timestamp", Instant.now());
        return problemDetail;
    }

    @ExceptionHandler(RiskEngineException.class)
    public ProblemDetail handleRiskEngineException(RiskEngineException ex) {
        // 403 Forbidden is appropriate for a risk rejection
        ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, ex.getMessage());
        problemDetail.setTitle("Risk Assessment Failed");
        problemDetail.setProperty("timestamp", Instant.now());
        return problemDetail;
    }
}
