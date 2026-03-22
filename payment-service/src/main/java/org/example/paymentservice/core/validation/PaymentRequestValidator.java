package org.example.paymentservice.core.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import lombok.extern.slf4j.Slf4j;
import org.example.paymentservice.dto.PaymentRequest;
import org.example.paymentservice.model.PaymentType;

@Slf4j
public class PaymentRequestValidator implements ConstraintValidator<ValidPaymentRequest, PaymentRequest> {
    @Override
    public boolean isValid(PaymentRequest request, ConstraintValidatorContext constraintValidatorContext) {
        if (request == null || request.type() == null) {
            return true;
        }

        PaymentType type = request.type();

        if ((type == PaymentType.TRANSFER || type == PaymentType.PAYMENT) && request.receiverId() == null) {
            return buildError(constraintValidatorContext, "receiverId", "Receiver ID is required for transfers and payments");
        }

        if ((type == PaymentType.DEPOSIT || type == PaymentType.WITHDRAWAL) && request.receiverId() != null) {
            log.warn("Deposit and Withdrawal transaction have no receiverId!");
        }

        return true;
    }

    private boolean buildError(ConstraintValidatorContext context, String node, String message) {
        context.disableDefaultConstraintViolation();
        context.buildConstraintViolationWithTemplate(message)
                .addPropertyNode(node)
                .addConstraintViolation();
        return false;
    }
}
