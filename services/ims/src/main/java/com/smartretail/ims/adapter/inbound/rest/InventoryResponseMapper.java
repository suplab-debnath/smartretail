package com.smartretail.ims.adapter.inbound.rest;

import com.smartretail.ims.adapter.in.web.generated.model.AlertSeverity;
import com.smartretail.ims.adapter.in.web.generated.model.AlertStatus;
import com.smartretail.ims.adapter.in.web.generated.model.AlertType;
import com.smartretail.ims.domain.model.InventoryPosition;
import com.smartretail.ims.domain.model.StockAlert;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

@Component
public class InventoryResponseMapper {

    public com.smartretail.ims.adapter.in.web.generated.model.InventoryPosition toApiModel(
            InventoryPosition pos) {
        var api = new com.smartretail.ims.adapter.in.web.generated.model.InventoryPosition();
        api.setPositionId(pos.getPositionId());
        api.setSkuId(pos.getSkuId());
        api.setDcId(pos.getDcId());
        api.setOnHand(pos.getOnHand());
        api.setInTransit(pos.getInTransit());
        api.setReserved(pos.getReserved());
        api.setReorderPoint(pos.getReorderPoint());
        api.setSafetyStock(pos.getSafetyStock());
        api.setVersion(pos.getVersion());
        if (pos.getLastUpdatedAt() != null) {
            api.setLastUpdatedAt(pos.getLastUpdatedAt().atOffset(ZoneOffset.UTC));
        }
        return api;
    }

    public com.smartretail.ims.adapter.in.web.generated.model.StockAlert toApiModel(StockAlert alert) {
        var api = new com.smartretail.ims.adapter.in.web.generated.model.StockAlert();
        api.setAlertId(alert.getAlertId());
        api.setPositionId(alert.getPositionId());
        api.setSkuId(alert.getSkuId());
        api.setDcId(alert.getDcId());
        api.setAlertType(AlertType.fromValue(alert.getAlertType().name()));
        api.setSeverity(AlertSeverity.fromValue(alert.getSeverity().name()));
        api.setThresholdValue(alert.getThresholdValue());
        api.setActualValue(alert.getActualValue());
        api.setStatus(AlertStatus.fromValue(alert.getStatus()));
        if (alert.getRaisedAt() != null) {
            api.setRaisedAt(OffsetDateTime.ofInstant(alert.getRaisedAt(), ZoneOffset.UTC));
        }
        return api;
    }
}
