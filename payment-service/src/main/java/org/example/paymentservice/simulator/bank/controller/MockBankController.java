package org.example.paymentservice.simulator.bank.controller;

import org.example.paymentservice.simulator.bank.dto.BankPaymentRequest;
import org.example.paymentservice.simulator.bank.dto.BankPaymentResponse;
import org.example.paymentservice.simulator.bank.dto.BankPaymentStatus;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@RestController
@RequestMapping("/mock-bank")
@Profile({"dev", "test"})
public class MockBankController {

    private final Map<UUID, BankPaymentResponse> idempotencyStore = new ConcurrentHashMap<>();

    @PostMapping("/pay")
    public BankPaymentResponse simulatePayment(@RequestBody BankPaymentRequest request) {

        if (idempotencyStore.containsKey(request.referenceId())) {
            return idempotencyStore.get(request.referenceId());
        }

        BankPaymentResponse response = new BankPaymentResponse(
                UUID.randomUUID(),
                BankPaymentStatus.APPROVED,
                "SUCCESS"
        );

        idempotencyStore.put(request.referenceId(), response);

        return response;
    }

    @GetMapping("/status/{referenceId}")
    public BankPaymentResponse statusCheck(@PathVariable UUID referenceId) {
        BankPaymentResponse response;

        if (idempotencyStore.containsKey(referenceId)) {
            response = idempotencyStore.get(referenceId);
        } else {
            response = new BankPaymentResponse(
                    UUID.randomUUID(),
                    BankPaymentStatus.NOT_FOUND,
                    "TRANSACTION NOT PROCESSED YET"
            );
        }

        return response;
    }

}
