package com.kalasetu.orders.service;

import com.kalasetu.orders.domain.ShippingDetails;
import com.kalasetu.orders.testsupport.TestFixtures;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static com.kalasetu.orders.testsupport.TestFixtures.line;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IdempotencyKeysTest {

    @Test
    void acceptsUuidKeys() {
        assertThatCode(() -> IdempotencyKeys.requireValid("3f2b8c1e-9a4d-4e6f-8b7a-1c2d3e4f5a6b"))
                .doesNotThrowAnyException();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"short", "has space in it", "slash/not/allowed", "emoji-🙂-key"})
    void rejectsMalformedKeys(String key) {
        assertThatThrownBy(() -> IdempotencyKeys.requireValid(key))
                .isInstanceOf(OrderException.class)
                .extracting("code").isEqualTo(ErrorCode.INVALID_IDEMPOTENCY_KEY);
    }

    @Test
    void rejectsKeysLongerThan128Characters() {
        assertThatThrownBy(() -> IdempotencyKeys.requireValid("a".repeat(129))).isInstanceOf(OrderException.class);
        assertThatCode(() -> IdempotencyKeys.requireValid("a".repeat(128))).doesNotThrowAnyException();
    }

    @Test
    void scopesKeysPerUser() {
        assertThat(IdempotencyKeys.storageKey("alice", "key-12345"))
                .isNotEqualTo(IdempotencyKeys.storageKey("bob", "key-12345"));
    }

    @Test
    void fingerprintIgnoresItemOrder() {
        String first = IdempotencyKeys.fingerprint(List.of(line("a", 1), line("b", 2)), TestFixtures.SHIPPING);
        String second = IdempotencyKeys.fingerprint(List.of(line("b", 2), line("a", 1)), TestFixtures.SHIPPING);

        assertThat(first).isEqualTo(second);
    }

    @Test
    void fingerprintChangesWithQuantityOrShipping() {
        String original = IdempotencyKeys.fingerprint(List.of(line("a", 1)), TestFixtures.SHIPPING);
        String otherQuantity = IdempotencyKeys.fingerprint(List.of(line("a", 2)), TestFixtures.SHIPPING);
        String otherAddress = IdempotencyKeys.fingerprint(List.of(line("a", 1)),
                new ShippingDetails("Asha Rao", "asha@example.com", "Somewhere else"));

        assertThat(original).isNotEqualTo(otherQuantity).isNotEqualTo(otherAddress);
    }

    @Test
    void fingerprintFieldsCannotBleedIntoEachOther() {
        String first = IdempotencyKeys.fingerprint(List.of(line("a", 1)),
                new ShippingDetails("Asha", "x@example.com", "Road"));
        String second = IdempotencyKeys.fingerprint(List.of(line("a", 1)),
                new ShippingDetails("Ash", "ax@example.com", "Road"));

        assertThat(first).isNotEqualTo(second);
    }
}
