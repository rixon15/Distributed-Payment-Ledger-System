package org.example.paymentservice.core.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import lombok.extern.slf4j.Slf4j;
import org.example.paymentservice.dto.PaymentRequest;
import org.example.paymentservice.model.TransactionType;

@Slf4j
public class PaymentRequestValidator implements ConstraintValidator<ValidPaymentRequest, PaymentRequest> {
    @Override
    public boolean isValid(PaymentRequest request, ConstraintValidatorContext constraintValidatorContext) {
        if (request == null || request.type() == null) {
            return true;
        }

        TransactionType type = request.type();

        if ((type == TransactionType.TRANSFER || type == TransactionType.PAYMENT) && request.receiverId() == null) {
            return buildError(constraintValidatorContext, "receiverId", "Receiver ID is required for transfers and payments");
        }

        if ((type == TransactionType.DEPOSIT || type == TransactionType.WITHDRAWAL) && request.receiverId() != null) {
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
