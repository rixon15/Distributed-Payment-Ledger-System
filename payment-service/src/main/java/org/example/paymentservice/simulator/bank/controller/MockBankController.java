package org.example.paymentservice.simulator.bank.controller;

import org.example.paymentservice.simulator.bank.dto.BankPaymentRequest;
import org.example.paymentservice.simulator.bank.dto.BankPaymentResponse;
import org.example.paymentservice.simulator.bank.dto.BankPaymentStatus;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

@RestController
@RequestMapping("/mock-bank")
@Profile({"dev", "test"})
public class MockBankController {

    private final Map<UUID, BankPaymentResponse> idempotencyStore = new ConcurrentHashMap<>();

    @PostMapping("/pay")
    public BankPaymentResponse simulatePayment(@RequestBody BankPaymentRequest request) throws InterruptedException {

        if (idempotencyStore.containsKey(request.referenceId())) {
            // Optional: You might want to log this to see it happening
            System.out.println("Mock Bank: Returning cached response for " + request.referenceId());
            return idempotencyStore.get(request.referenceId());
        }

        long latency = ThreadLocalRandom.current().nextLong(1000, 3000);
        Thread.sleep(latency);

        int roll = ThreadLocalRandom.current().nextInt(100);
        BankPaymentResponse response;

//        if (roll < 10) {
//            response = new BankPaymentResponse(
//                    UUID.randomUUID(),
//                    BankPaymentStatus.DECLINED,
//                    "INSUFFICIENT_FUNDS"
//            );
//        } else if (roll < 15) {
//            throw new BankErrorException();
//        } else {
        response = new BankPaymentResponse(
                UUID.randomUUID(),
                BankPaymentStatus.APPROVED,
                "SUCCESS"
        );
//        }

        idempotencyStore.put(request.referenceId(), response);

        return response;
    }


}
