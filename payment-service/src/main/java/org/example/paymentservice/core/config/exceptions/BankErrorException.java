package org.example.paymentservice.core.config.exceptions;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
public class BankErrorException extends RuntimeException {

    public BankErrorException() {
        super("Simulated bank 500 error");
    }

}
