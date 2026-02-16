package org.example.paymentservice.core.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.*;

@Target({ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = PaymentRequestValidator.class)
@Documented
public @interface ValidPaymentRequest {

    String message() default  "Invalid payment request for the specific transaction type";
    Class<?>[] groups() default {};
    Class<? extends Payload>[] payload() default {};

}
