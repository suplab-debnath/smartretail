package com.smartretail.lambda.batchpostprocessor;

import org.junit.jupiter.api.Test;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ForecastRowPayloadTest {

    @Test
    void shouldCreateValidPayload() {
        assertDoesNotThrow(() -> new ForecastRowPayload(
                "SKU-001", "DC-1", LocalDate.now(), 30, 10, 20, 30
        ));
    }

    @Test
    void shouldThrowOnNullOrBlankSkuId() {
        assertThrows(IllegalArgumentException.class, () -> new ForecastRowPayload(
                null, "DC-1", LocalDate.now(), 30, 10, 20, 30
        ));
        assertThrows(IllegalArgumentException.class, () -> new ForecastRowPayload(
                "   ", "DC-1", LocalDate.now(), 30, 10, 20, 30
        ));
    }

    @Test
    void shouldThrowOnNullOrBlankDcId() {
        assertThrows(IllegalArgumentException.class, () -> new ForecastRowPayload(
                "SKU-001", null, LocalDate.now(), 30, 10, 20, 30
        ));
        assertThrows(IllegalArgumentException.class, () -> new ForecastRowPayload(
                "SKU-001", "  ", LocalDate.now(), 30, 10, 20, 30
        ));
    }

    @Test
    void shouldThrowOnNullForecastDate() {
        assertThrows(IllegalArgumentException.class, () -> new ForecastRowPayload(
                "SKU-001", "DC-1", null, 30, 10, 20, 30
        ));
    }

    @Test
    void shouldThrowOnNonPositiveHorizonDays() {
        assertThrows(IllegalArgumentException.class, () -> new ForecastRowPayload(
                "SKU-001", "DC-1", LocalDate.now(), 0, 10, 20, 30
        ));
        assertThrows(IllegalArgumentException.class, () -> new ForecastRowPayload(
                "SKU-001", "DC-1", LocalDate.now(), -1, 10, 20, 30
        ));
    }

    @Test
    void shouldThrowOnNegativePValues() {
        assertThrows(IllegalArgumentException.class, () -> new ForecastRowPayload(
                "SKU-001", "DC-1", LocalDate.now(), 30, -1, 20, 30
        ));
        assertThrows(IllegalArgumentException.class, () -> new ForecastRowPayload(
                "SKU-001", "DC-1", LocalDate.now(), 30, 10, -5, 30
        ));
        assertThrows(IllegalArgumentException.class, () -> new ForecastRowPayload(
                "SKU-001", "DC-1", LocalDate.now(), 30, 10, 20, -10
        ));
    }
}
