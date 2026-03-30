package org.example.paymentservice.service;

import org.example.paymentservice.dto.PaymentRequest;

import java.util.List;
import java.util.UUID;

/**
 * Orchestration contract for payment lifecycle processing and recovery.
 */
public interface PaymentService {

    /**
     * Processes a newly submitted payment request.
     */
    void processPayment(UUID senderId, PaymentRequest request);

    /**
     * Resumes processing for a previously stuck payment.
     */
    void resumeProcessing(UUID paymentId);

    /**
     * Claims a bounded set of stale pending payments for recovery.
     */
    List<UUID> claimStuckPayments(int batchSize);
}
