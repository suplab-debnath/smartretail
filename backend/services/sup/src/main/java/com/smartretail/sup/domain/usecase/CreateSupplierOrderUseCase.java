package com.smartretail.sup.domain.usecase;

import com.smartretail.sup.port.inbound.CreateSupplierOrderPort;
import com.smartretail.sup.port.outbound.SupplierOrderWritePort;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
public class CreateSupplierOrderUseCase implements CreateSupplierOrderPort {

    private final SupplierOrderWritePort supplierOrderWritePort;

    public CreateSupplierOrderUseCase(SupplierOrderWritePort supplierOrderWritePort) {
        this.supplierOrderWritePort = supplierOrderWritePort;
    }

    @Override
    public UUID createSupplierOrder(Command command) {
        return supplierOrderWritePort.insertSupplierOrder(
                command.poId(),
                command.supplierId(),
                command.skuId(),
                command.dcId(),
                command.quantity());
    }
}
