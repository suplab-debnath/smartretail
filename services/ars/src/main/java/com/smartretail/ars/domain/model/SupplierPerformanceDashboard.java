package com.smartretail.ars.domain.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Pure domain record — zero infrastructure imports. */
public record SupplierPerformanceDashboard(
        List<SupplierEntry> suppliers,
        Instant dataFreshness) {

    public record SupplierEntry(
            UUID supplierId,
            String supplierName,
            BigDecimal onTimeDeliveryRate,
            BigDecimal poAcknowledgementSlaCompliance,
            int openExceptions,
            BigDecimal avgLeadTimeVarianceDays,
            int totalPoCount,
            BigDecimal totalPoValue) {}
}
