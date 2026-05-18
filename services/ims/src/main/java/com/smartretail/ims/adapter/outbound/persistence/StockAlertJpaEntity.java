package com.smartretail.ims.adapter.outbound.persistence;

import com.smartretail.ims.domain.model.AlertSeverity;
import com.smartretail.ims.domain.model.AlertType;
import com.smartretail.ims.domain.model.StockAlert;
import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "stock_alerts", schema = "inventory")
public class StockAlertJpaEntity {

    @Id
    @Column(name = "alert_id", nullable = false)
    private UUID alertId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "position_id", nullable = false)
    private InventoryPositionJpaEntity position;

    @Column(name = "alert_type", nullable = false, length = 20)
    private String alertType;

    @Column(name = "severity", nullable = false, length = 10)
    private String severity;

    @Column(name = "threshold_value", nullable = false)
    private int thresholdValue;

    @Column(name = "actual_value", nullable = false)
    private int actualValue;

    @Column(name = "status", nullable = false, length = 20)
    private String status;

    @Column(name = "raised_at", nullable = false)
    private Instant raisedAt;

    protected StockAlertJpaEntity() {
    }

    public static StockAlertJpaEntity from(StockAlert alert, InventoryPositionJpaEntity position) {
        StockAlertJpaEntity e = new StockAlertJpaEntity();
        e.alertId = alert.getAlertId();
        e.position = position;
        e.alertType = alert.getAlertType().name();
        e.severity = alert.getSeverity().name();
        e.thresholdValue = alert.getThresholdValue();
        e.actualValue = alert.getActualValue();
        e.status = alert.getStatus();
        e.raisedAt = alert.getRaisedAt();
        return e;
    }

    public StockAlert toDomain() {
        return StockAlert.fromDb(
                alertId,
                position.getPositionId(),
                position.getSkuId(),
                position.getDcId(),
                AlertType.valueOf(alertType),
                AlertSeverity.valueOf(severity),
                thresholdValue,
                actualValue,
                status,
                raisedAt);
    }

    public UUID getAlertId() {
        return alertId;
    }
}
