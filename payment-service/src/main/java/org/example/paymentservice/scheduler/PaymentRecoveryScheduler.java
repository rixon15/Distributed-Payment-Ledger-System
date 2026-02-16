package org.example.paymentservice.scheduler;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.paymentservice.model.Payment;
import org.example.paymentservice.model.PaymentStatus;
import org.example.paymentservice.repository.PaymentRepository;
import org.example.paymentservice.service.PaymentService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Component
@Slf4j
@RequiredArgsConstructor
public class PaymentRecoveryScheduler {

    private final PaymentRepository paymentRepository;
    private final PaymentService paymentService;

    @Scheduled(fixedDelay = 300_000)
    @Transactional
    public void recoverStuckPayment() {

        LocalDateTime threshold = LocalDateTime.now().minusMinutes(5);
        List<Payment> stuckPayments = paymentRepository.findAllByStatusAndCreatedAtBefore(PaymentStatus.PENDING, threshold);

        if(stuckPayments.isEmpty()) return;

        for(Payment payment : stuckPayments) {
            try {
                paymentService.resumeProcessing(payment);
            } catch (Exception e) {
                log.error("Critical: Failed to recover payment: {}", payment.getId(), e);
            }
        }

    }

}
