package org.example.gatewayservice.auth.exception;

public class TokenResolutionException extends RuntimeException{

    public TokenResolutionException(String message) {
        super(message);
    }
}
