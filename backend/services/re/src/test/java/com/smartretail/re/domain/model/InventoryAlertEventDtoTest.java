package com.smartretail.re.domain.model;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class InventoryAlertEventDtoTest {

    @Test
    void constructor_andAccessors() {
        String alertId     = UUID.randomUUID().toString();
        String positionId  = UUID.randomUUID().toString();

        InventoryAlertEventDto dto = new InventoryAlertEventDto(
                alertId, positionId, "SKU-BEV-001", "DC-LONDON",
                "LOW_STOCK", "HIGH", 50, 10, "ACTIVE");

        assertThat(dto.alertId()).isEqualTo(alertId);
        assertThat(dto.positionId()).isEqualTo(positionId);
        assertThat(dto.skuId()).isEqualTo("SKU-BEV-001");
        assertThat(dto.dcId()).isEqualTo("DC-LONDON");
        assertThat(dto.alertType()).isEqualTo("LOW_STOCK");
        assertThat(dto.severity()).isEqualTo("HIGH");
        assertThat(dto.thresholdValue()).isEqualTo(50);
        assertThat(dto.actualValue()).isEqualTo(10);
        assertThat(dto.status()).isEqualTo("ACTIVE");
    }
}
