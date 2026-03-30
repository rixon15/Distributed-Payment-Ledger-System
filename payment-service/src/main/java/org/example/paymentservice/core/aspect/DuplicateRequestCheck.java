package org.example.paymentservice.core.aspect;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.example.paymentservice.core.annotation.Idempotent;
import org.example.paymentservice.core.exception.DuplicatedRequestException;
import org.example.paymentservice.dto.PaymentRequest;
import org.example.paymentservice.service.RequestLockService;
import org.springframework.stereotype.Component;

/**
 * Aspect-based idempotency guard for endpoints annotated with {@link Idempotent}.
 *
 * <p>Uses Redis lock acquisition on the request idempotency key and rejects concurrent
 * duplicates before business processing starts.
 */
@Aspect
@Component
@Slf4j
@RequiredArgsConstructor
public class DuplicateRequestCheck {

    private final RequestLockService requestLockService;

    /**
     * Acquires and releases request lock around annotated method execution.
     *
     * @throws DuplicatedRequestException when the key is already locked
     */
    @Around("@annotation(idempotent)")
    public Object checkDuplicateRequest(ProceedingJoinPoint joinPoint, Idempotent idempotent) throws Throwable {
        PaymentRequest request = findPaymentRequest(joinPoint.getArgs());

        if (request == null) {
            log.warn("Method marked @Idempotent but no PaymentRequest found in arguments.");
            return joinPoint.proceed();
        }

        String key = request.idempotencyKey();

        if (!requestLockService.acquire(key)) {
            throw new DuplicatedRequestException(request.idempotencyKey());
        }

        try {
            return joinPoint.proceed();
        } catch (Throwable ex) {
            log.error("Execution failed for key {}. Releasing lock.", key);
            throw ex;
        } finally {
            requestLockService.release(key);
        }
    }

    private PaymentRequest findPaymentRequest(Object[] args) {
        for (Object arg : args) {
            if (arg instanceof PaymentRequest req) return req;
        }
        return null;
    }
}
