package com.kalasetu.orders.domain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MoneyTest {

    @Test
    void convertsWholeRupees() {
        assertThat(Money.rupeesToPaise(1200L)).isEqualTo(120_000L);
        assertThat(Money.rupeesToPaise(5)).isEqualTo(500L);
    }

    @Test
    void convertsDecimalRupeesWithoutFloatingPointDrift() {
        // 0.1 + 0.2 style errors would turn 19.99 into 1998.9999...
        assertThat(Money.rupeesToPaise(19.99)).isEqualTo(1999L);
        assertThat(Money.rupeesToPaise(0.29)).isEqualTo(29L);
        assertThat(Money.rupeesToPaise(1299.5)).isEqualTo(129_950L);
    }

    @Test
    void roundsFractionsOfAPaisaHalfUp() {
        assertThat(Money.rupeesToPaise(10.005)).isEqualTo(1001L);
        assertThat(Money.rupeesToPaise(10.004)).isEqualTo(1000L);
    }

    @Test
    void acceptsNumericStrings() {
        assertThat(Money.rupeesToPaise(" 450.25 ")).isEqualTo(45_025L);
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-10", "abc", ""})
    void rejectsNonPositiveOrNonNumericStrings(String value) {
        assertThatThrownBy(() -> Money.rupeesToPaise(value)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsMissingOrUnsupportedValues() {
        assertThatThrownBy(() -> Money.rupeesToPaise(null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Money.rupeesToPaise(true)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Money.rupeesToPaise(0.0)).isInstanceOf(IllegalArgumentException.class);
    }
}
