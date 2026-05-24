package com.smartretail.sup.adapter.inbound.rest;

import com.smartretail.sup.adapter.in.web.generated.model.ShipmentStatus;
import com.smartretail.sup.adapter.in.web.generated.model.SupplierOrder;
import com.smartretail.sup.adapter.in.web.generated.model.SupplierOrderListResponse;
import com.smartretail.sup.domain.model.SupplierOrderList;
import org.mapstruct.Mapper;

import java.time.ZoneOffset;
import java.util.List;

@Mapper(componentModel = "spring")
public interface SupplierOrderResponseMapper {

    default SupplierOrderListResponse toResponse(SupplierOrderList result) {
        List<SupplierOrder> orders = result.orders().stream()
                .map(o -> {
                    SupplierOrder order = new SupplierOrder(
                            o.supplierPoId(),
                            o.poId(),
                            o.supplierId(),
                            o.supplierName(),
                            o.skuId(),
                            o.dcId(),
                            o.quantity(),
                            ShipmentStatus.fromValue(o.shipmentStatus())
                    );
                    if (o.confirmedAt() != null)  order.setConfirmedAt(o.confirmedAt().atOffset(ZoneOffset.UTC));
                    if (o.dispatchedAt() != null) order.setDispatchedAt(o.dispatchedAt().atOffset(ZoneOffset.UTC));
                    if (o.eta() != null)          order.setEta(o.eta());
                    if (o.lastUpdateAt() != null) order.setLastUpdateAt(o.lastUpdateAt().atOffset(ZoneOffset.UTC));
                    return order;
                })
                .toList();

        return new SupplierOrderListResponse(orders, result.dataFreshness().atOffset(ZoneOffset.UTC));
    }
}
