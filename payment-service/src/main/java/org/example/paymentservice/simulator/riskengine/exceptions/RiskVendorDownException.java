package org.example.paymentservice.simulator.riskengine.exceptions;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
public class RiskVendorDownException extends RuntimeException {

    public RiskVendorDownException() {
        super("Risk Vendor Unavailable");
    }

}
