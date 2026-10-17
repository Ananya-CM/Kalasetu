package com.kalasetu.orders.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Amounts are handled as whole paise ({@code long}) everywhere in the service. Product prices are
 * stored by the existing app as rupee numbers, so they are converted exactly once, here.
 */
public final class Money {

    public static final String CURRENCY_INR = "INR";
    private static final int PAISE_DECIMAL_PLACES = 2;

    private Money() {
    }

    /**
     * Converts a rupee value as stored in Firestore (a {@link Number} or a numeric {@link String})
     * into paise, rounding half-up to the nearest paisa.
     *
     * @throws IllegalArgumentException if the value is missing, not numeric, or not positive
     */
    public static long rupeesToPaise(Object rupees) {
        BigDecimal value = toBigDecimal(rupees);
        if (value.signum() <= 0) {
            throw new IllegalArgumentException("Price must be positive: " + rupees);
        }
        return value.setScale(PAISE_DECIMAL_PLACES, RoundingMode.HALF_UP)
                .movePointRight(PAISE_DECIMAL_PLACES)
                .longValueExact();
    }

    private static BigDecimal toBigDecimal(Object rupees) {
        if (rupees == null) {
            throw new IllegalArgumentException("Price is missing");
        }
        try {
            if (rupees instanceof Long || rupees instanceof Integer) {
                return BigDecimal.valueOf(((Number) rupees).longValue());
            }
            if (rupees instanceof Number number) {
                // BigDecimal.valueOf(double) uses the shortest decimal representation, so 12.3 stays 12.3.
                return BigDecimal.valueOf(number.doubleValue());
            }
            if (rupees instanceof String text) {
                return new BigDecimal(text.trim());
            }
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Price is not a number: " + rupees, e);
        }
        throw new IllegalArgumentException("Unsupported price type: " + rupees.getClass().getSimpleName());
    }
}
