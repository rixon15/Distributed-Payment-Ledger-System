package org.example.paymentservice.scheduler;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.paymentservice.service.PaymentService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Periodic recovery worker for stuck pending payments.
 *
 * <p>Claims stale rows and retries orchestration through {@code resumeProcessing}.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class PaymentRecoveryScheduler {

    private final PaymentService paymentService;

    /**
     * Runs recovery cycle every fixed delay interval.
     */
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
