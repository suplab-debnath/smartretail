package com.smartretail.ars.domain.usecase;

import com.smartretail.ars.domain.model.SupplierOrdersDashboard;
import com.smartretail.ars.domain.model.SupplierOrdersDashboard.SupplierOrderEntry;
import com.smartretail.ars.port.inbound.SupplierOrdersDashboardPort;
import com.smartretail.ars.port.outbound.SupplierReadPort;
import com.smartretail.ars.port.outbound.SupplierReadPort.SupplierOrderRow;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

@Service
public class SupplierOrdersDashboardUseCase implements SupplierOrdersDashboardPort {

    private final SupplierReadPort supplierReadPort;

    public SupplierOrdersDashboardUseCase(SupplierReadPort supplierReadPort) {
        this.supplierReadPort = supplierReadPort;
    }

    @Override
    public SupplierOrdersDashboard assemble(String status) {
        List<SupplierOrderRow> rows = supplierReadPort.findSupplierOrders(status);
        List<SupplierOrderEntry> entries = rows.stream()
                .map(r -> new SupplierOrderEntry(
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
        return new SupplierOrdersDashboard(entries, Instant.now());
    }
}
