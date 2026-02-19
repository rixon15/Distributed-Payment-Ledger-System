package org.example.paymentservice.core.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.BAD_REQUEST)
public class InvalidTransferException extends RuntimeException{
    public InvalidTransferException(String msg) {super(msg);}
}
