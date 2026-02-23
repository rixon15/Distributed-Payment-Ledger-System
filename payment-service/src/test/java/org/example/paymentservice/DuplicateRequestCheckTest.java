package org.example.paymentservice;

import org.aspectj.lang.ProceedingJoinPoint;
import org.example.paymentservice.core.annotation.Idempotent;
import org.example.paymentservice.core.aspect.DuplicateRequestCheck;
import org.example.paymentservice.core.exception.DuplicatedRequestException;
import org.example.paymentservice.dto.PaymentRequest;
import org.example.paymentservice.model.TransactionType;
import org.example.paymentservice.service.RequestLockService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class DuplicateRequestCheckTest {

    @Mock
    private RequestLockService lockService;

    @Mock
    private ProceedingJoinPoint joinPoint;

    @Mock
    private Idempotent idempotentAnnotation;

    @InjectMocks
    private DuplicateRequestCheck aspect;

    private final String KEY = "test-idempotency-key";

    @BeforeEach
    void setUp() {
        PaymentRequest paymentRequest = new PaymentRequest(
                UUID.randomUUID(),
                KEY,
                TransactionType.TRANSFER,
                BigDecimal.TEN,
                "USD"
        );

        lenient().when(joinPoint.getArgs()).thenReturn(new Object[]{paymentRequest});
    }

    @Test
    @DisplayName("Should proceed when lock is acquired successfully")
    void testCheckDuplicateRequest_Success() throws Throwable {
        when(lockService.acquire(KEY)).thenReturn(true);
        when(joinPoint.proceed()).thenReturn("SuccessResponse");

        Object result = aspect.checkDuplicateRequest(joinPoint, idempotentAnnotation);

        assertThat(result).isEqualTo("SuccessResponse");
        verify(lockService).acquire(KEY);
    }

    @Test
    @DisplayName("Should throw DuplicatedRequestException when lock acquisition fails")
    void testCheckDuplicateRequest_Duplicate() throws Throwable {
        when(lockService.acquire(KEY)).thenReturn(false);

        assertThatThrownBy(() -> aspect.checkDuplicateRequest(joinPoint, idempotentAnnotation))
                .isInstanceOf(DuplicatedRequestException.class);

        verify(joinPoint, never()).proceed();
    }

    @Test
    @DisplayName("Should release lock if the controller method throws an exception")
    void testCheckDuplicateRequest_ReleaseOnFailure() throws Throwable {
        when(lockService.acquire(KEY)).thenReturn(true);
        when(joinPoint.proceed()).thenThrow(new RuntimeException("DB down"));

        assertThatThrownBy(() -> aspect.checkDuplicateRequest(joinPoint, idempotentAnnotation))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("DB down");

        verify(lockService).release(KEY);
    }
}
