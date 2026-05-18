package com.smartretail.ims.adapter.outbound.persistence;

import com.smartretail.ims.domain.model.InventoryPosition;
import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "inventory_positions", schema = "inventory")
public class InventoryPositionJpaEntity {

    @Id
    @Column(name = "position_id", nullable = false)
    private UUID positionId;

    @Column(name = "sku_id", nullable = false, length = 50)
    private String skuId;

    @Column(name = "dc_id", nullable = false, length = 50)
    private String dcId;

    @Column(name = "on_hand", nullable = false)
    private int onHand;

    @Column(name = "in_transit", nullable = false)
    private int inTransit;

    @Column(name = "reserved", nullable = false)
    private int reserved;

    @Column(name = "reorder_point", nullable = false)
    private int reorderPoint;

    @Column(name = "safety_stock", nullable = false)
    private int safetyStock;

    // Plain column — version managed manually via decrementOnHand native query
    @Column(name = "version", nullable = false)
    private int version;

    @Column(name = "last_updated_at", nullable = false)
    private Instant lastUpdatedAt;

    protected InventoryPositionJpaEntity() {
    }

    public InventoryPosition toDomain() {
        InventoryPosition pos = new InventoryPosition();
        pos.setPositionId(positionId);
        pos.setSkuId(skuId);
        pos.setDcId(dcId);
        pos.setOnHand(onHand);
        pos.setInTransit(inTransit);
        pos.setReserved(reserved);
        pos.setReorderPoint(reorderPoint);
        pos.setSafetyStock(safetyStock);
        pos.setVersion(version);
        pos.setLastUpdatedAt(lastUpdatedAt);
        return pos;
    }

    public UUID getPositionId() {
        return positionId;
    }

    public String getSkuId() {
        return skuId;
    }

    public String getDcId() {
        return dcId;
    }

    public int getOnHand() {
        return onHand;
    }

    public int getVersion() {
        return version;
    }
}
