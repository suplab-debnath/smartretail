package com.smartretail.ims.domain.model;

import java.time.Instant;
import java.util.UUID;

public class InventoryPosition {

    private UUID positionId;
    private String skuId;
    private String dcId;
    private int onHand;
    private int inTransit;
    private int reserved;
    private int reorderPoint;
    private int safetyStock;
    private int version;
    private Instant lastUpdatedAt;

    public InventoryPosition() {}

    public int getAvailableToPromise() {
        return onHand - reserved;
    }

    public boolean isLowStock() {
        return getAvailableToPromise() < reorderPoint;
    }

    public AlertSeverity computeSeverity() {
        int atp = getAvailableToPromise();
        if (atp <= 0)                         return AlertSeverity.CRITICAL;
        if (atp < (reorderPoint * 0.5))       return AlertSeverity.HIGH;
        return AlertSeverity.MEDIUM;
    }

    public UUID getPositionId()     { return positionId; }
    public String getSkuId()        { return skuId; }
    public String getDcId()         { return dcId; }
    public int getOnHand()          { return onHand; }
    public int getInTransit()       { return inTransit; }
    public int getReserved()        { return reserved; }
    public int getReorderPoint()    { return reorderPoint; }
    public int getSafetyStock()     { return safetyStock; }
    public int getVersion()         { return version; }
    public Instant getLastUpdatedAt() { return lastUpdatedAt; }

    public void setPositionId(UUID positionId)     { this.positionId = positionId; }
    public void setSkuId(String skuId)             { this.skuId = skuId; }
    public void setDcId(String dcId)               { this.dcId = dcId; }
    public void setOnHand(int onHand)              { this.onHand = onHand; }
    public void setInTransit(int inTransit)        { this.inTransit = inTransit; }
    public void setReserved(int reserved)          { this.reserved = reserved; }
    public void setReorderPoint(int reorderPoint)  { this.reorderPoint = reorderPoint; }
    public void setSafetyStock(int safetyStock)    { this.safetyStock = safetyStock; }
    public void setVersion(int version)            { this.version = version; }
    public void setLastUpdatedAt(Instant t)        { this.lastUpdatedAt = t; }
}
