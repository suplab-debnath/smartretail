package com.smartretail.ars.port.outbound;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public interface SupplierReadPort {

    /** Active suppliers: supplierId → supplierName */
    Map<UUID, String> findActiveSupplierNames();

    /** Early/On-Time/Late counts per supplier from supplier.supplier_pos (dispatched_at vs eta). */
    List<SupplierDeliveryStats> findDeliveryStats();

    /**
     * Full shipment metrics per supplier for Flow 9 scorecard.
     * Query runs within supplier schema only — no cross-schema join.
     */
    List<ShipmentMetricsRow> findShipmentMetricsBySupplierId();

    /**
     * Lead time variance (actual dispatch vs approved + lead_time_days) per supplier.
     * Query runs within supplier schema only.
     */
    Map<UUID, BigDecimal> findAvgLeadTimeVarianceBySupplierId();

    /**
     * Count of supplier POs in EXCEPTION status per supplierId.
     */
    Map<UUID, Integer> findOpenExceptionsBySupplierId();

    record SupplierDeliveryStats(UUID supplierId, int earlyCount, int onTimeCount, int lateCount) {}

    record ShipmentMetricsRow(UUID supplierId, int onTimeCount, int totalShipped) {}
}
