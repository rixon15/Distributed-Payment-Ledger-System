package org.example.paymentservice.scheduler;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.paymentservice.service.PaymentService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Component
@Slf4j
@RequiredArgsConstructor
public class PaymentRecoveryScheduler {

    private final PaymentService paymentService;

    @Scheduled(fixedDelay = 300_000)
    @Transactional
    public void recoverStuckPayment() {

        List<UUID> batch = paymentService.claimStuckPayments(10);

        if (batch.isEmpty()) return;

        log.info("Recovering {} stuck payments", batch.size());

        for (UUID paymentId : batch) {
            try {
                paymentService.resumeProcessing(paymentId);
            } catch (Exception e) {
                log.error("Failed to recover payment: {}", paymentId, e);
            }
        }
    }
}
