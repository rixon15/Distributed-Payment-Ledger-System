package org.example.paymentservice.model;

import lombok.Getter;

/**
 * Supported payment currencies represented by ISO-like codes.
 */
@Getter
public enum CurrencyType {
    USD("USD"),
    EUR("EUR"),
    RON("RON");

    private final String code;

    CurrencyType(String code) {
        this.code = code;
    }

}
