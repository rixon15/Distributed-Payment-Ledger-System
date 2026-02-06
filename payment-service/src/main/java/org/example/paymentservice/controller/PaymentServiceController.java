package org.example.paymentservice.controller;

import jakarta.validation.Valid;
import org.example.paymentservice.dto.PaymentRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/paymnet")
public class PaymentServiceController {

    @PostMapping("/transfer")
    public ResponseEntity<Void> transfer(
            @RequestHeader("X-User-ID") UUID senderID,
            @RequestBody @Valid PaymentRequest request
            ) {
        return null;
    }

}
