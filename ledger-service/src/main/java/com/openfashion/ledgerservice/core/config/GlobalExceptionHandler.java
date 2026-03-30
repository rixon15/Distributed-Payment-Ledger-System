package com.openfashion.ledgerservice.core.config;

import com.openfashion.ledgerservice.core.exceptions.*;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(InsufficientFundsException.class)
    public ResponseEntity<Object> handleNSF(InsufficientFundsException ex) {
        return buildResponse(HttpStatus.PAYMENT_REQUIRED, "INSUFFICIENT_FUNDS", ex.getMessage());
    }

    @ExceptionHandler(AccountInactiveException.class)
    public ResponseEntity<Object> handleInactive(AccountInactiveException ex) {
        return buildResponse(HttpStatus.FORBIDDEN, "ACCOUNT_INACTIVE", ex.getMessage());
    }

    @ExceptionHandler(AccountNotFoundException.class)
    public ResponseEntity<Object> handleNotFound(AccountNotFoundException ex) {
        return buildResponse(HttpStatus.NOT_FOUND, "ACCOUNT_NOT_FOUND", ex.getMessage());
    }

    @ExceptionHandler(TransactionNotFoundException.class)
    public ResponseEntity<Object> handleTxNotFound(TransactionNotFoundException ex) {
        return buildResponse(HttpStatus.NOT_FOUND, "TRANSACTION_NOT_FOUND", ex.getMessage());
    }

    @ExceptionHandler(DataMismatchException.class)
    public ResponseEntity<Object> handleMismatch(DataMismatchException ex) {
        return buildResponse(HttpStatus.CONFLICT, "DATA_MISMATCH", ex.getMessage());
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