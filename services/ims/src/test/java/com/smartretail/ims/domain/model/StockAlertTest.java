package com.smartretail.ims.domain.model;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class StockAlertTest {

    private InventoryPosition position(int onHand, int reserved, int reorderPoint) {
        InventoryPosition p = new InventoryPosition();
        p.setPositionId(UUID.randomUUID());
        p.setSkuId("SKU-001");
        p.setDcId("DC-LONDON");
        p.setOnHand(onHand);
        p.setReserved(reserved);
        p.setReorderPoint(reorderPoint);
        return p;
    }

    @Test
    void create_populatesFieldsFromPosition() {
        InventoryPosition pos = position(10, 5, 50);
        StockAlert alert = StockAlert.create(pos, AlertType.LOW_STOCK, AlertSeverity.HIGH);

        assertThat(alert.getAlertId()).isNotNull();
        assertThat(alert.getSkuId()).isEqualTo("SKU-001");
        assertThat(alert.getDcId()).isEqualTo("DC-LONDON");
        assertThat(alert.getAlertType()).isEqualTo(AlertType.LOW_STOCK);
        assertThat(alert.getSeverity()).isEqualTo(AlertSeverity.HIGH);
        assertThat(alert.getStatus()).isEqualTo("ACTIVE");
        assertThat(alert.getActualValue()).isEqualTo(5);   // onHand - reserved
        assertThat(alert.getThresholdValue()).isEqualTo(50);
        assertThat(alert.getRaisedAt()).isNotNull();
    }

    @Test
    void fromDb_rehydratesAllFields() {
        UUID alertId    = UUID.randomUUID();
        UUID positionId = UUID.randomUUID();
        Instant now     = Instant.now();

        StockAlert alert = StockAlert.fromDb(alertId, positionId, "SKU-002", "DC-PARIS",
                AlertType.OVERSTOCK, AlertSeverity.CRITICAL,
                50, 0, "ACTIVE", now);

        assertThat(alert.getAlertId()).isEqualTo(alertId);
        assertThat(alert.getPositionId()).isEqualTo(positionId);
        assertThat(alert.getSkuId()).isEqualTo("SKU-002");
        assertThat(alert.getDcId()).isEqualTo("DC-PARIS");
        assertThat(alert.getAlertType()).isEqualTo(AlertType.OVERSTOCK);
        assertThat(alert.getSeverity()).isEqualTo(AlertSeverity.CRITICAL);
        assertThat(alert.getThresholdValue()).isEqualTo(50);
        assertThat(alert.getActualValue()).isEqualTo(0);
        assertThat(alert.getStatus()).isEqualTo("ACTIVE");
        assertThat(alert.getRaisedAt()).isEqualTo(now);
    }
}
