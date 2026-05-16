package com.smartretail.ims.port.outbound;

import com.smartretail.ims.domain.model.InventoryPosition;
import com.smartretail.ims.domain.model.StockAlert;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Outbound port: read/write inventory positions and stock alerts. */
public interface InventoryRepositoryPort {
    Optional<InventoryPosition> findBySkuAndDc(String skuId, String dcId);
    Optional<InventoryPosition> findById(UUID positionId);
    int decrementOnHand(UUID positionId, int quantity, int currentVersion);
    void saveAlert(StockAlert alert);
    List<InventoryPosition> findPositions(String dcId, String skuId, int page, int size);
    long countPositions(String dcId, String skuId);
    List<StockAlert> findAlerts(String dcId, String severity, String status, int page, int size);
    long countAlerts(String dcId, String severity, String status);
}
