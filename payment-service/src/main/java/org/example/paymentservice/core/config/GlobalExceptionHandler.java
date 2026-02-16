package org.example.paymentservice.core.config;

import org.example.paymentservice.core.exception.DuplicatedRequestException;
import org.example.paymentservice.core.exception.RiskEngineException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;


@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(RiskEngineException.class)
    public ResponseEntity<Object> handleRiskEngineException(RiskEngineException ex) {
        return buildResponse(ex.getStatus(), "RISK_FAILURE", ex.getMessage());
    }

    @ExceptionHandler(DuplicatedRequestException.class)
    public ResponseEntity<Object> handleDuplicatedRequestException(DuplicatedRequestException ex) {
        return buildResponse(HttpStatus.CONFLICT, "DUPLICATED_REQUEST", ex.getMessage());
    }

    private ResponseEntity<Object> buildResponse(HttpStatus status, String code, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("timestamp", Instant.now());
        body.put("status", status.value());
        body.put("error", code);
        body.put("message", message);
        return new ResponseEntity<>(body, status);
    }
}
