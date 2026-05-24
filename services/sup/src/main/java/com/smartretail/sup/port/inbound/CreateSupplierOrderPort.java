package com.smartretail.sup.port.inbound;

import java.util.UUID;

public interface CreateSupplierOrderPort {

    record Command(UUID poId, UUID supplierId, String skuId, String dcId, int quantity) {}

    UUID createSupplierOrder(Command command);
}
