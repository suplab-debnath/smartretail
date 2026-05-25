package com.smartretail.ars.domain.model;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Pure domain record — zero infrastructure imports. */
public record SupplierOrdersDashboard(
        List<SupplierOrderEntry> orders,
        Instant dataFreshness) {

    public record SupplierOrderEntry(
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
