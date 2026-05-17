package com.smartretail.ims.adapter.inbound.rest;

import com.smartretail.ims.domain.model.InventoryPosition;
import com.smartretail.ims.domain.model.StockAlert;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface InventoryResponseMapper {

    @Mapping(target = "lastUpdatedAt",
             expression = "java(pos.getLastUpdatedAt() != null ? pos.getLastUpdatedAt().atOffset(java.time.ZoneOffset.UTC) : null)")
    com.smartretail.ims.adapter.in.web.generated.model.InventoryPosition toApiModel(InventoryPosition pos);

    @Mapping(target = "alertType",
             expression = "java(com.smartretail.ims.adapter.in.web.generated.model.AlertType.fromValue(alert.getAlertType().name()))")
    @Mapping(target = "severity",
             expression = "java(com.smartretail.ims.adapter.in.web.generated.model.AlertSeverity.fromValue(alert.getSeverity().name()))")
    @Mapping(target = "status",
             expression = "java(com.smartretail.ims.adapter.in.web.generated.model.AlertStatus.fromValue(alert.getStatus()))")
    @Mapping(target = "raisedAt",
             expression = "java(alert.getRaisedAt() != null ? java.time.OffsetDateTime.ofInstant(alert.getRaisedAt(), java.time.ZoneOffset.UTC) : null)")
    com.smartretail.ims.adapter.in.web.generated.model.StockAlert toApiModel(StockAlert alert);
}
