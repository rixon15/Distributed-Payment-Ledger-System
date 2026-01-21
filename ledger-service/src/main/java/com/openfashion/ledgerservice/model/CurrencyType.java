package com.openfashion.ledgerservice.model;

import lombok.Getter;

import java.util.Currency;

@Getter
public enum CurrencyType {
    USD("USD"),
    EUR("EUR"),
    RON("RON");

    private final String code;

    CurrencyType(String code) {
        this.code = code;
    }

    public Currency toJavaCurrency() {
        return Currency.getInstance(code);
    }

}
