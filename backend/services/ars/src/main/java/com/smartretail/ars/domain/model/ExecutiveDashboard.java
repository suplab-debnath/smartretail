package com.smartretail.ars.domain.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Pure domain record — zero infrastructure imports. */
public record ExecutiveDashboard(
        ForecastAccuracy forecastAccuracy,
        StockoutFrequency stockoutFrequency,
        ReplenishmentCycleTime replenishmentCycleTime,
        OnTimeDelivery onTimeDelivery,
        List<SupplierPerformanceEntry> supplierPerformance,
        Instant dataFreshness) {

    public record ForecastAccuracy(
            BigDecimal latestMape,
            Trend trend,
            List<MapeDataPoint> history) {}

    public record MapeDataPoint(LocalDate runDate, BigDecimal mape) {}

    public record StockoutDataPoint(LocalDate alertDate, int criticalCount) {}

    public record StockoutFrequency(int last30Days, DirectionTrend trend, List<StockoutDataPoint> history) {}

    public record CycleTimeDataPoint(LocalDate weekStart, BigDecimal averageDays, int poCount) {}

    public record ReplenishmentCycleTime(BigDecimal averageDays, Trend trend, List<CycleTimeDataPoint> history) {}

    public record OnTimeDelivery(BigDecimal rate, Trend trend) {}

    public record SupplierPerformanceEntry(
            UUID supplierId,
            String supplierName,
            BigDecimal otdRate,
            BigDecimal fillRate,
            int earlyCount,
            int onTimeCount,
            int lateCount,
            int openExceptions) {}

    public enum Trend { IMPROVING, STABLE, DEGRADING }

    public enum DirectionTrend { INCREASING, STABLE, DECREASING }
}
