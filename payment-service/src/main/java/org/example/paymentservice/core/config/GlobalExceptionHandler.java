package org.example.paymentservice.core.config;

import org.example.paymentservice.core.exception.RiskEngineException;
import org.example.paymentservice.dto.ErrorResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(RiskEngineException.class)
    public ResponseEntity<ErrorResponse> handleRiskEngineException(RiskEngineException ex) {

        ErrorResponse body = new ErrorResponse(ex.getMessage(), ex.getStatus().value());

        return ResponseEntity.status(ex.getStatus()).body(body);
    }

}
