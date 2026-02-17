package org.example.paymentservice.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.example.paymentservice.dto.PaymentRequest;
import org.example.paymentservice.service.PaymentService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/payment")
@RequiredArgsConstructor
public class PaymentServiceController {

    private final PaymentService paymentService;

    @PostMapping("/transfer")
    public ResponseEntity<Void> transfer(
            @RequestHeader("X-User-ID") UUID senderID,
            @RequestBody @Valid PaymentRequest request
    ) {
        paymentService.processPayment(senderID, request);

        return new ResponseEntity<>(HttpStatus.ACCEPTED);
    }

}
