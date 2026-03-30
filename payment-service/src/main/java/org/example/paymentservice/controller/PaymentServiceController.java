package org.example.paymentservice.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.example.paymentservice.core.annotation.Idempotent;
import org.example.paymentservice.dto.PaymentRequest;
import org.example.paymentservice.service.PaymentService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * REST entrypoint for payment execution requests.
 *
 * <p>Accepted requests are guarded by idempotency aspect logic and then forwarded
 * into the payment orchestration service.
 */
@RestController
@RequestMapping("/payments")
@RequiredArgsConstructor
public class PaymentServiceController {

    private final PaymentService paymentService;

    /**
     * Starts asynchronous payment processing.
     *
     * @param senderID caller identity from request header
     * @param request validated payment request payload
     * @return HTTP 202 when request is accepted for processing
     */
    @PostMapping("/execute")
    @Idempotent
    public ResponseEntity<Void> executePayment(
            @RequestHeader("X-User-ID") UUID senderID,
            @RequestBody @Valid PaymentRequest request
            ) {
        paymentService.processPayment(senderID, request);

        return new ResponseEntity<>(HttpStatus.ACCEPTED);
    }

}
