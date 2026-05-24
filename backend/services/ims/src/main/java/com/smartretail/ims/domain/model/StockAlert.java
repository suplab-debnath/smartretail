package com.smartretail.ims.domain.model;

import java.time.Instant;
import java.util.UUID;

public class StockAlert {

    private UUID alertId;
    private UUID positionId;
    private String skuId;
    private String dcId;
    private AlertType alertType;
    private AlertSeverity severity;
    private int thresholdValue;
    private int actualValue;
    private String status;
    private Instant raisedAt;

    StockAlert() {}

    public static StockAlert create(InventoryPosition position, AlertType type, AlertSeverity severity) {
        StockAlert alert = new StockAlert();
        alert.alertId       = UUID.randomUUID();
        alert.positionId    = position.getPositionId();
        alert.skuId         = position.getSkuId();
        alert.dcId          = position.getDcId();
        alert.alertType     = type;
        alert.severity      = severity;
        alert.thresholdValue = position.getReorderPoint();
        alert.actualValue   = position.getAvailableToPromise();
        alert.status        = "ACTIVE";
        alert.raisedAt      = Instant.now();
        return alert;
    }

    /** Factory for rehydrating a StockAlert loaded from the database. */
    public static StockAlert fromDb(UUID alertId, UUID positionId, String skuId, String dcId,
                                    AlertType alertType, AlertSeverity severity,
                                    int thresholdValue, int actualValue,
                                    String status, Instant raisedAt) {
        StockAlert a = new StockAlert();
        a.alertId        = alertId;
        a.positionId     = positionId;
        a.skuId          = skuId;
        a.dcId           = dcId;
        a.alertType      = alertType;
        a.severity       = severity;
        a.thresholdValue = thresholdValue;
        a.actualValue    = actualValue;
        a.status         = status;
        a.raisedAt       = raisedAt;
        return a;
    }

    public UUID getAlertId()        { return alertId; }
    public UUID getPositionId()     { return positionId; }
    public String getSkuId()        { return skuId; }
    public String getDcId()         { return dcId; }
    public AlertType getAlertType() { return alertType; }
    public AlertSeverity getSeverity()  { return severity; }
    public int getThresholdValue()  { return thresholdValue; }
    public int getActualValue()     { return actualValue; }
    public String getStatus()       { return status; }
    public Instant getRaisedAt()    { return raisedAt; }
}
