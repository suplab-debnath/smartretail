package com.smartretail.sup.domain.model;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Domain model for supplier PO tracking list.
 * Data sourced entirely from supplier schema — no cross-schema joins.
 */
public record SupplierOrderList(
        List<SupplierOrder> orders,
        Instant dataFreshness) {

    public record SupplierOrder(
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
