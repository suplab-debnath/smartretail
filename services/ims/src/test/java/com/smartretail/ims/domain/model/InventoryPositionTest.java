package com.smartretail.ims.domain.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class InventoryPositionTest {

    private com.smartretail.ims.domain.model.InventoryPosition position(int onHand, int reserved, int reorderPoint) {
        InventoryPosition p = new InventoryPosition();
        p.setOnHand(onHand);
        p.setReserved(reserved);
        p.setReorderPoint(reorderPoint);
        return p;
    }

    @Test
    void getAvailableToPromise_returnsOnHandMinusReserved() {
        assertThat(position(100, 30, 50).getAvailableToPromise()).isEqualTo(70);
    }

    @Test
    void isLowStock_trueWhenAtpBelowReorderPoint() {
        assertThat(position(60, 40, 50).isLowStock()).isTrue(); // atp=20, reorder=50
    }

    @Test
    void isLowStock_falseWhenAtpAboveReorderPoint() {
        assertThat(position(100, 10, 50).isLowStock()).isFalse(); // atp=90, reorder=50
    }

    @Test
    void computeSeverity_criticalWhenAtpZeroOrNegative() {
        assertThat(position(10, 10, 50).computeSeverity()).isEqualTo(AlertSeverity.CRITICAL);
        assertThat(position(5, 10, 50).computeSeverity()).isEqualTo(AlertSeverity.CRITICAL);
    }

    @Test
    void computeSeverity_highWhenAtpBelowHalfReorderPoint() {
        // atp=15, reorderPoint=50, half=25 → atp < 25 → HIGH
        assertThat(position(25, 10, 50).computeSeverity()).isEqualTo(AlertSeverity.HIGH);
    }

    @Test
    void computeSeverity_mediumWhenAtpAboveHalfReorderPoint() {
        // atp=30, reorderPoint=50, half=25 → atp >= 25 but < 50 → MEDIUM
        assertThat(position(40, 10, 50).computeSeverity()).isEqualTo(AlertSeverity.MEDIUM);
    }
}
