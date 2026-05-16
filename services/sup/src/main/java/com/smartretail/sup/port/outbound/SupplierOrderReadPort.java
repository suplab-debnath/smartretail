package com.smartretail.sup.port.outbound;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Outbound port for supplier order read queries.
 * All SQL is confined to the supplier schema — no cross-schema JOINs.
 */
public interface SupplierOrderReadPort {

    /**
     * Returns supplier POs joined with their latest shipment update and supplier name.
     * All joins are within the supplier schema.
     *
     * @param shipmentStatus optional filter — null returns all statuses
     */
    List<SupplierOrderRow> findSupplierOrders(String shipmentStatus);

    /**
     * Returns MAX(last_update_at) across all supplier POs for data freshness.
     */
    Instant findDataFreshness();

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
}
