package com.smartretail.dfs.domain.model;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class ForecastRowTest {

    private static final LocalDate DATE = LocalDate.of(2026, 6, 1);

    @Test
    void shouldConstructValidRow() {
        ForecastRow row = new ForecastRow("SKU-BEV-001", "DC-LONDON", DATE, 30, 80, 100, 120);
        assertThat(row.skuId()).isEqualTo("SKU-BEV-001");
        assertThat(row.dcId()).isEqualTo("DC-LONDON");
        assertThat(row.horizonDays()).isEqualTo(30);
        assertThat(row.p10()).isEqualTo(80);
        assertThat(row.p50()).isEqualTo(100);
        assertThat(row.p90()).isEqualTo(120);
    }

    @Test
    void shouldThrowWhenSkuIdIsNull() {
        assertThatNullPointerException()
                .isThrownBy(() -> new ForecastRow(null, "DC-LONDON", DATE, 30, 0, 0, 0))
                .withMessage("skuId must not be null");
    }

    @Test
    void shouldThrowWhenDcIdIsNull() {
        assertThatNullPointerException()
                .isThrownBy(() -> new ForecastRow("SKU-BEV-001", null, DATE, 30, 0, 0, 0))
                .withMessage("dcId must not be null");
    }

    @Test
    void shouldThrowWhenForecastDateIsNull() {
        assertThatNullPointerException()
                .isThrownBy(() -> new ForecastRow("SKU-BEV-001", "DC-LONDON", null, 30, 0, 0, 0))
                .withMessage("forecastDate must not be null");
    }

    @Test
    void shouldThrowWhenSkuIdIsBlank() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new ForecastRow("  ", "DC-LONDON", DATE, 30, 0, 0, 0))
                .withMessage("skuId must not be blank");
    }

    @Test
    void shouldThrowWhenDcIdIsBlank() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new ForecastRow("SKU-BEV-001", "", DATE, 30, 0, 0, 0))
                .withMessage("dcId must not be blank");
    }

    @Test
    void shouldThrowWhenHorizonDaysIsZero() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new ForecastRow("SKU-BEV-001", "DC-LONDON", DATE, 0, 0, 0, 0))
                .withMessage("horizonDays must be > 0");
    }

    @Test
    void shouldThrowWhenHorizonDaysIsNegative() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new ForecastRow("SKU-BEV-001", "DC-LONDON", DATE, -1, 0, 0, 0))
                .withMessage("horizonDays must be > 0");
    }

    @Test
    void shouldThrowWhenP10IsNegative() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new ForecastRow("SKU-BEV-001", "DC-LONDON", DATE, 30, -1, 0, 0))
                .withMessage("p10 must be >= 0");
    }

    @Test
    void shouldThrowWhenP50IsNegative() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new ForecastRow("SKU-BEV-001", "DC-LONDON", DATE, 30, 0, -1, 0))
                .withMessage("p50 must be >= 0");
    }

    @Test
    void shouldThrowWhenP90IsNegative() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new ForecastRow("SKU-BEV-001", "DC-LONDON", DATE, 30, 0, 0, -1))
                .withMessage("p90 must be >= 0");
    }

    @Test
    void shouldAllowZeroPercentiles() {
        ForecastRow row = new ForecastRow("SKU-BEV-001", "DC-LONDON", DATE, 1, 0, 0, 0);
        assertThat(row.p10()).isZero();
        assertThat(row.p50()).isZero();
        assertThat(row.p90()).isZero();
    }
}
