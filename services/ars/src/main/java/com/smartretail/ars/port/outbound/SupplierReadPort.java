package com.smartretail.ars.port.outbound;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public interface SupplierReadPort {

    /** Active suppliers: supplierId → supplierName */
    Map<UUID, String> findActiveSupplierNames();

    /** Early/On-Time/Late counts per supplier from supplier.supplier_pos (dispatched_at vs eta). */
    List<SupplierDeliveryStats> findDeliveryStats();

    record SupplierDeliveryStats(UUID supplierId, int earlyCount, int onTimeCount, int lateCount) {}
}
