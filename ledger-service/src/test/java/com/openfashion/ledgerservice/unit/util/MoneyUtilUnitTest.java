package com.openfashion.ledgerservice.unit.util;

import com.openfashion.ledgerservice.core.util.MoneyUtil;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.RoundingMode;

import static org.assertj.core.api.Assertions.assertThat;

class MoneyUtilUnitTest {

    @Test
    void format_shouldReturnZeroWhenAmountIsNull() {
        BigDecimal result = MoneyUtil.format(null);

        assertThat(result).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void format_shouldPadScaleToFourDecimalPlaces() {
        BigDecimal result = MoneyUtil.format(new BigDecimal("12.3"));

        assertThat(result).isEqualByComparingTo("12.3000");
    }

    @Test
    void format_shouldKeepValueWhenAlreadyAtFourDecimalPlaces() {
        BigDecimal result = MoneyUtil.format(new BigDecimal("12.3456"));

        assertThat(result).isEqualByComparingTo("12.3456");
    }

    @Test
    void format_shouldRoundDownWhenFifthDecimalIsLessThanFive() {
        BigDecimal result = MoneyUtil.format(new BigDecimal("12.34564"));

        assertThat(result).isEqualByComparingTo("12.3456");
    }

    @Test
    void format_shouldKeepTieWhenRetainedDigitIsEven() {
        BigDecimal result = MoneyUtil.format(new BigDecimal("12.34565"));

        assertThat(result).isEqualByComparingTo("12.3456");
    }

    @Test
    void format_shouldRoundTieWhenRetainedDigitIsOdd() {
        BigDecimal result = MoneyUtil.format(new BigDecimal("12.34575"));

        assertThat(result).isEqualByComparingTo("12.3458");
    }

    @Test
    void format_shouldRoundUpWhenGreaterThanHalf() {
        BigDecimal result = MoneyUtil.format(new BigDecimal("12.34576"));

        assertThat(result).isEqualByComparingTo("12.3458");
    }

    @Test
    void format_shouldRoundNegativeValuesUsingSameRules() {
        BigDecimal result = MoneyUtil.format(new BigDecimal("-12.34576"));

        assertThat(result).isEqualByComparingTo("-12.3458");
    }

    @Test
    void constants_shouldExposeExpectedScaleAndRoundingMode() {
        assertThat(MoneyUtil.SCALE).isEqualTo(4);
        assertThat(MoneyUtil.ROUNDING).isEqualTo(RoundingMode.HALF_EVEN);
    }
}