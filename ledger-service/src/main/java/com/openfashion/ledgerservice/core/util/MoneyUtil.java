package com.openfashion.ledgerservice.core.util;

import java.math.BigDecimal;
import java.math.RoundingMode;

public class MoneyUtil {

    private MoneyUtil(){}

    public static final int SCALE = 4;
    public static final RoundingMode ROUNDING = RoundingMode.HALF_EVEN;

    public static BigDecimal format(BigDecimal amount) {
        if (amount == null) return BigDecimal.ZERO;
        return amount.setScale(SCALE, ROUNDING);
    }

}
