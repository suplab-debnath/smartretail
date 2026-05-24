package com.smartretail.sup.domain.usecase;

import com.smartretail.sup.domain.model.SupplierRecordList;
import com.smartretail.sup.domain.model.SupplierRecordList.SupplierEntry;
import com.smartretail.sup.port.inbound.SupplierQueryPort;
import com.smartretail.sup.port.outbound.SupplierReadPort;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class SupplierQueryUseCase implements SupplierQueryPort {

    private final SupplierReadPort supplierReadPort;

    public SupplierQueryUseCase(SupplierReadPort supplierReadPort) {
        this.supplierReadPort = supplierReadPort;
    }

    @Override
    public SupplierRecordList getSuppliers() {
        List<SupplierEntry> entries = supplierReadPort.findAllSuppliers().stream()
                .map(r -> new SupplierEntry(r.supplierId(), r.supplierName()))
                .toList();
        return new SupplierRecordList(entries);
    }
}
