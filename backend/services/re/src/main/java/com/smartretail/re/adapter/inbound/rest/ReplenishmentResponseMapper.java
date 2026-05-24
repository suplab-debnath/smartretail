package com.smartretail.re.adapter.inbound.rest;

import com.smartretail.re.adapter.inbound.rest.generated.model.PoLineItem;
import com.smartretail.re.adapter.inbound.rest.generated.model.PurchaseOrder;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import java.util.List;

@Mapper(componentModel = "spring")
public interface ReplenishmentResponseMapper {

    @Mapping(target = "totalValue",
             expression = "java(po.getTotalValue() != null ? po.getTotalValue().doubleValue() : null)")
    @Mapping(target = "workflowStatus",
             expression = "java(com.smartretail.re.adapter.inbound.rest.generated.model.WorkflowStatus.fromValue(po.getWorkflowStatus().name()))")
    @Mapping(target = "approvedAt",
             expression = "java(po.getApprovedAt() != null ? java.time.OffsetDateTime.ofInstant(po.getApprovedAt(), java.time.ZoneOffset.UTC) : null)")
    @Mapping(target = "rejectedAt",
             expression = "java(po.getRejectedAt() != null ? java.time.OffsetDateTime.ofInstant(po.getRejectedAt(), java.time.ZoneOffset.UTC) : null)")
    @Mapping(target = "createdAt",
             expression = "java(po.getCreatedAt() != null ? java.time.OffsetDateTime.ofInstant(po.getCreatedAt(), java.time.ZoneOffset.UTC) : null)")
    @Mapping(target = "updatedAt",
             expression = "java(po.getUpdatedAt() != null ? java.time.OffsetDateTime.ofInstant(po.getUpdatedAt(), java.time.ZoneOffset.UTC) : null)")
    @Mapping(target = "lineItems", ignore = true)
    PurchaseOrder toApiModel(com.smartretail.re.domain.model.PurchaseOrder po);

    default PurchaseOrder toApiModel(com.smartretail.re.domain.model.PurchaseOrder po,
                                     List<com.smartretail.re.domain.model.PoLineItem> lineItems) {
        PurchaseOrder api = toApiModel(po);
        if (lineItems != null && !lineItems.isEmpty()) {
            api.setLineItems(lineItems.stream().map(this::toApiModel).toList());
        }
        return api;
    }

    @Mapping(target = "unitCost",
             expression = "java(item.getUnitCost() != null ? item.getUnitCost().doubleValue() : null)")
    @Mapping(target = "lineTotal",
             expression = "java(item.getLineTotal() != null ? item.getLineTotal().doubleValue() : null)")
    PoLineItem toApiModel(com.smartretail.re.domain.model.PoLineItem item);
}
