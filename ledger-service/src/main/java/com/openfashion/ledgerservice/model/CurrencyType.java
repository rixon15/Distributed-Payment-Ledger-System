package com.openfashion.ledgerservice.model;

import lombok.Getter;

import java.util.Currency;

/**
 * Supported ledger currencies.
 *
 * <p>Wraps an ISO-like currency code and provides conversion to {@link java.util.Currency}.
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
