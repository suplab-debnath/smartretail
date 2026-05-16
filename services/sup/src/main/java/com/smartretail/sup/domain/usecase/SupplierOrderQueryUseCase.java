package com.smartretail.sup.domain.usecase;

import com.smartretail.sup.domain.model.SupplierOrderList;
import com.smartretail.sup.domain.model.SupplierOrderList.SupplierOrder;
import com.smartretail.sup.port.inbound.SupplierOrderQueryPort;
import com.smartretail.sup.port.outbound.SupplierOrderReadPort;
import com.smartretail.sup.port.outbound.SupplierOrderReadPort.SupplierOrderRow;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

/**
 * Assembles the supplier order list with data freshness.
 * Single-schema reads only (supplier schema via SupplierOrderReadPort).
 */
@Service
public class SupplierOrderQueryUseCase implements SupplierOrderQueryPort {

    private final SupplierOrderReadPort supplierOrderReadPort;

    public SupplierOrderQueryUseCase(SupplierOrderReadPort supplierOrderReadPort) {
        this.supplierOrderReadPort = supplierOrderReadPort;
    }

    @Override
    public SupplierOrderList getSupplierOrders(String shipmentStatus) {
        List<SupplierOrderRow> rows = supplierOrderReadPort.findSupplierOrders(shipmentStatus);
        Instant freshness = supplierOrderReadPort.findDataFreshness();

        List<SupplierOrder> orders = rows.stream()
                .map(r -> new SupplierOrder(
                        r.supplierPoId(),
                        r.poId(),
                        r.supplierId(),
                        r.supplierName(),
                        r.skuId(),
                        r.dcId(),
                        r.quantity(),
                        r.shipmentStatus(),
                        r.confirmedAt(),
                        r.dispatchedAt(),
                        r.eta(),
                        r.lastUpdateAt()))
                .toList();

        return new SupplierOrderList(orders, freshness);
    }
}
