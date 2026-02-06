package org.example.paymentservice.simulator.bank.controller;

import org.example.paymentservice.simulator.bank.dto.BankPaymentRequest;
import org.example.paymentservice.simulator.bank.dto.BankPaymentResponse;
import org.example.paymentservice.simulator.bank.dto.BankPaymentStatus;
import org.example.paymentservice.simulator.bank.exceptions.BankErrorException;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

@RestController
@RequestMapping("/mock-bank")
@Profile({"dev", "test"})
public class MockBankController {

    @PostMapping("/pay")
    public BankPaymentResponse simulatePayment(@RequestBody BankPaymentRequest request) throws InterruptedException {
        long latency = ThreadLocalRandom.current().nextLong(1000, 3000);
        Thread.sleep(latency);

        int roll = ThreadLocalRandom.current().nextInt(100);

        if (roll < 10) {
            return new BankPaymentResponse(
                    UUID.randomUUID(),
                    BankPaymentStatus.DECLINED,
                    "INSUFFICIENT_FUNDS"
            );
        } else if (roll < 15) {
            throw new BankErrorException();
        }

        return new BankPaymentResponse(
                UUID.randomUUID(),
                BankPaymentStatus.APPROVED,
                "SUCCESS"
        );
    }


}
