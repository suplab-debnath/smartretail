package com.smartretail.re.adapter.inbound.rest;

import com.smartretail.re.adapter.inbound.rest.generated.model.PoLineItem;
import com.smartretail.re.adapter.inbound.rest.generated.model.PurchaseOrder;
import com.smartretail.re.adapter.inbound.rest.generated.model.WorkflowStatus;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

@Component
public class ReplenishmentResponseMapper {

    public PurchaseOrder toApiModel(com.smartretail.re.domain.model.PurchaseOrder po,
                                    List<com.smartretail.re.domain.model.PoLineItem> lineItems) {
        var api = new PurchaseOrder();
        api.setPoId(po.getPoId());
        api.setRuleId(po.getRuleId());
        api.setSupplierId(po.getSupplierId());
        api.setSkuId(po.getSkuId());
        api.setDcId(po.getDcId());
        api.setQuantity(po.getQuantity());
        api.setTotalValue(po.getTotalValue() != null ? po.getTotalValue().doubleValue() : null);
        api.setWorkflowStatus(WorkflowStatus.fromValue(po.getWorkflowStatus().name()));
        api.setVersion(po.getVersion());
        api.setAlertId(po.getAlertId());
        api.setApprovedBy(po.getApprovedBy());
        if (po.getApprovedAt() != null) {
            api.setApprovedAt(OffsetDateTime.ofInstant(po.getApprovedAt(), ZoneOffset.UTC));
        }
        api.setRejectedBy(po.getRejectedBy());
        if (po.getRejectedAt() != null) {
            api.setRejectedAt(OffsetDateTime.ofInstant(po.getRejectedAt(), ZoneOffset.UTC));
        }
        api.setRejectionReason(po.getRejectionReason());
        if (po.getCreatedAt() != null) {
            api.setCreatedAt(OffsetDateTime.ofInstant(po.getCreatedAt(), ZoneOffset.UTC));
        }
        if (po.getUpdatedAt() != null) {
            api.setUpdatedAt(OffsetDateTime.ofInstant(po.getUpdatedAt(), ZoneOffset.UTC));
        }
        if (lineItems != null && !lineItems.isEmpty()) {
            api.setLineItems(lineItems.stream().map(this::toApiModel).toList());
        }
        return api;
    }

    public PurchaseOrder toApiModel(com.smartretail.re.domain.model.PurchaseOrder po) {
        return toApiModel(po, List.of());
    }

    public PoLineItem toApiModel(com.smartretail.re.domain.model.PoLineItem item) {
        var api = new PoLineItem();
        api.setLineId(item.getLineId());
        api.setSkuId(item.getSkuId());
        api.setQuantity(item.getQuantity());
        api.setUnitCost(item.getUnitCost() != null ? item.getUnitCost().doubleValue() : null);
        api.setLineTotal(item.getLineTotal() != null ? item.getLineTotal().doubleValue() : null);
        return api;
    }
}
