package com.smartretail.sis.domain.model;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SalesTransactionTest {

    private static final UUID ID    = UUID.randomUUID();
    private static final Instant TS = Instant.parse("2026-05-15T14:23:00Z");

    @Test
    void validTransaction_createsSuccessfully() {
        assertThatCode(() -> new SalesTransaction(
                ID, "STORE-001", "SKU-BEV-001", "DC-LONDON",
                1, BigDecimal.valueOf(0.01), SalesTransaction.Channel.POS, TS))
                .doesNotThrowAnyException();
    }

    @Test
    void quantity_zero_throwsIllegalArgumentException() {
        assertThatThrownBy(() -> new SalesTransaction(
                ID, "STORE-001", "SKU-BEV-001", "DC-LONDON",
                0, BigDecimal.ONE, SalesTransaction.Channel.POS, TS))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("quantity must be > 0");
    }

    @Test
    void quantity_negative_throwsIllegalArgumentException() {
        assertThatThrownBy(() -> new SalesTransaction(
                ID, "STORE-001", "SKU-BEV-001", "DC-LONDON",
                -5, BigDecimal.ONE, SalesTransaction.Channel.POS, TS))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("quantity must be > 0");
    }

    @Test
    void unitPrice_negative_throwsIllegalArgumentException() {
        assertThatThrownBy(() -> new SalesTransaction(
                ID, "STORE-001", "SKU-BEV-001", "DC-LONDON",
                10, new BigDecimal("-0.01"), SalesTransaction.Channel.POS, TS))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unitPrice must be >= 0");
    }

    @Test
    void unitPrice_zero_isAllowed() {
        // Zero price is valid (clearance / free samples)
        assertThatCode(() -> new SalesTransaction(
                ID, "STORE-001", "SKU-BEV-001", "DC-LONDON",
                10, BigDecimal.ZERO, SalesTransaction.Channel.POS, TS))
                .doesNotThrowAnyException();
    }

    @Test
    void nullTransactionId_throwsNullPointerException() {
        assertThatThrownBy(() -> new SalesTransaction(
                null, "STORE-001", "SKU-BEV-001", "DC-LONDON",
                10, BigDecimal.ONE, SalesTransaction.Channel.POS, TS))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void ecommerceChannel_isRecognised() {
        SalesTransaction tx = new SalesTransaction(
                ID, "STORE-001", "SKU-BEV-001", "DC-LONDON",
                10, BigDecimal.ONE, SalesTransaction.Channel.ECOMMERCE, TS);
        org.assertj.core.api.Assertions.assertThat(tx.channel())
                .isEqualTo(SalesTransaction.Channel.ECOMMERCE);
    }
}
