package com.smartretail.ars.port.outbound;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public interface SupplierReadPort {

    /** Active suppliers: supplierId → supplierName */
    Map<UUID, String> findActiveSupplierNames();

    /**
     * Supplier PO list for the SC Planner order tracking tab.
     * Query runs within the supplier schema only — no cross-schema SQL join.
     *
     * @param status filter by po_status; {@code null} returns all statuses
     */
    List<SupplierOrderRow> findSupplierOrders(String status);

    record SupplierOrderRow(
            UUID supplierPoId,
            UUID poId,
            UUID supplierId,
            String supplierName,
            String skuId,
            String dcId,
            int quantity,
            String shipmentStatus,
            Instant confirmedAt,
            Instant dispatchedAt,
            LocalDate eta,
            Instant lastUpdateAt) {}

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
