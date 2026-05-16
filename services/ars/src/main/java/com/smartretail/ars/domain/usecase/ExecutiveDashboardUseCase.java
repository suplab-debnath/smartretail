package com.smartretail.ars.domain.usecase;

import com.smartretail.ars.domain.model.ExecutiveDashboard;
import com.smartretail.ars.domain.model.ExecutiveDashboard.*;
import com.smartretail.ars.port.inbound.ExecutiveDashboardPort;
import com.smartretail.ars.port.outbound.ForecastReadPort;
import com.smartretail.ars.port.outbound.InventoryReadPort;
import com.smartretail.ars.port.outbound.ReplenishmentReadPort;
import com.smartretail.ars.port.outbound.SupplierReadPort;
import com.smartretail.ars.port.outbound.SupplierReadPort.SupplierDeliveryStats;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

@Service
public class ExecutiveDashboardUseCase implements ExecutiveDashboardPort {

    private static final int HISTORY_LIMIT = 30;
    private static final int TREND_WINDOW_DAYS = 7;
    private static final BigDecimal TREND_THRESHOLD = BigDecimal.valueOf(0.005);

    private final ForecastReadPort forecastReadPort;
    private final InventoryReadPort inventoryReadPort;
    private final ReplenishmentReadPort replenishmentReadPort;
    private final SupplierReadPort supplierReadPort;

    public ExecutiveDashboardUseCase(
            ForecastReadPort forecastReadPort,
            InventoryReadPort inventoryReadPort,
            ReplenishmentReadPort replenishmentReadPort,
            SupplierReadPort supplierReadPort) {
        this.forecastReadPort = forecastReadPort;
        this.inventoryReadPort = inventoryReadPort;
        this.replenishmentReadPort = replenishmentReadPort;
        this.supplierReadPort = supplierReadPort;
    }

    @Override
    public ExecutiveDashboard assemble() {
        // Parallel reads — no cross-schema SQL joins (Architecture rule #1)
        CompletableFuture<List<MapeDataPoint>> forecastFuture =
                CompletableFuture.supplyAsync(() -> forecastReadPort.findRecentMapeHistory(HISTORY_LIMIT));

        CompletableFuture<int[]> stockoutFuture =
                CompletableFuture.supplyAsync(() -> new int[]{
                        inventoryReadPort.countCriticalAlerts(30),
                        inventoryReadPort.countCriticalAlerts(60)
                });

        CompletableFuture<Optional<BigDecimal>> cycleTimeFuture =
                CompletableFuture.supplyAsync(() -> replenishmentReadPort.averageCycleTimeDays(90));

        CompletableFuture<List<StockoutDataPoint>> stockoutHistoryFuture =
                CompletableFuture.supplyAsync(() -> inventoryReadPort.findDailyCriticalAlertHistory(30));

        CompletableFuture<List<CycleTimeDataPoint>> cycleHistoryFuture =
                CompletableFuture.supplyAsync(() -> replenishmentReadPort.findWeeklyCycleTimeHistory(90));

        CompletableFuture<List<SupplierDeliveryStats>> supplierStatsFuture =
                CompletableFuture.supplyAsync(() -> supplierReadPort.findDeliveryStats());

        CompletableFuture<Map<UUID, int[]>> fillRateFuture =
                CompletableFuture.supplyAsync(() -> replenishmentReadPort.fillRateBySupplier(90));

        CompletableFuture.allOf(forecastFuture, stockoutFuture, cycleTimeFuture,
                stockoutHistoryFuture, cycleHistoryFuture, supplierStatsFuture, fillRateFuture).join();

        List<MapeDataPoint> history = forecastFuture.join();
        int[] stockoutCounts = stockoutFuture.join();
        Optional<BigDecimal> cycleTime = cycleTimeFuture.join();
        List<StockoutDataPoint> stockoutHistory = stockoutHistoryFuture.join();
        List<CycleTimeDataPoint> cycleHistory = cycleHistoryFuture.join();
        List<SupplierDeliveryStats> deliveryStats = supplierStatsFuture.join();
        Map<UUID, int[]> fillRates = fillRateFuture.join();

        // Supplier names fetched synchronously — cheap single-table read
        Map<UUID, String> supplierNames = supplierReadPort.findActiveSupplierNames();

        List<SupplierPerformanceEntry> suppliers = buildSupplierPerformance(supplierNames, deliveryStats, fillRates);

        return new ExecutiveDashboard(
                buildForecastAccuracy(history),
                buildStockoutFrequency(stockoutCounts, stockoutHistory),
                buildCycleTime(cycleTime, cycleHistory),
                buildOnTimeDelivery(deliveryStats),
                suppliers,
                Instant.now()
        );
    }

    private ForecastAccuracy buildForecastAccuracy(List<MapeDataPoint> history) {
        BigDecimal latestMape = history.isEmpty()
                ? BigDecimal.ZERO
                : history.getFirst().mape();
        return new ForecastAccuracy(latestMape, mapetrend(history), history);
    }

    private Trend mapetrend(List<MapeDataPoint> history) {
        if (history.size() < TREND_WINDOW_DAYS * 2) {
            return Trend.STABLE;
        }
        BigDecimal recent = average(history.subList(0, TREND_WINDOW_DAYS));
        BigDecimal prior  = average(history.subList(TREND_WINDOW_DAYS, TREND_WINDOW_DAYS * 2));
        BigDecimal delta  = prior.subtract(recent); // positive = MAPE fell = IMPROVING
        if (delta.compareTo(TREND_THRESHOLD) > 0)  return Trend.IMPROVING;
        if (delta.compareTo(TREND_THRESHOLD.negate()) < 0) return Trend.DEGRADING;
        return Trend.STABLE;
    }

    private BigDecimal average(List<MapeDataPoint> points) {
        return points.stream()
                .map(MapeDataPoint::mape)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(points.size()), 6, RoundingMode.HALF_UP);
    }

    private StockoutFrequency buildStockoutFrequency(int[] counts, List<StockoutDataPoint> history) {
        int last30  = counts[0];
        int prior30 = counts[1] - counts[0]; // 60-day total minus last-30
        DirectionTrend trend;
        if (last30 < prior30)      trend = DirectionTrend.DECREASING;
        else if (last30 > prior30) trend = DirectionTrend.INCREASING;
        else                       trend = DirectionTrend.STABLE;
        return new StockoutFrequency(last30, trend, history);
    }

    private ReplenishmentCycleTime buildCycleTime(Optional<BigDecimal> avgDays, List<CycleTimeDataPoint> history) {
        BigDecimal days = avgDays.orElse(BigDecimal.ZERO)
                .setScale(1, RoundingMode.HALF_UP);
        return new ReplenishmentCycleTime(days, Trend.STABLE, history);
    }

    private OnTimeDelivery buildOnTimeDelivery(List<SupplierDeliveryStats> stats) {
        int totalOnTime = stats.stream().mapToInt(s -> s.earlyCount() + s.onTimeCount()).sum();
        int totalAll = stats.stream().mapToInt(s -> s.earlyCount() + s.onTimeCount() + s.lateCount()).sum();
        BigDecimal rate = totalAll == 0
                ? BigDecimal.ZERO
                : BigDecimal.valueOf(totalOnTime).divide(BigDecimal.valueOf(totalAll), 4, RoundingMode.HALF_UP);
        return new OnTimeDelivery(rate, Trend.STABLE);
    }

    private List<SupplierPerformanceEntry> buildSupplierPerformance(
            Map<UUID, String> names,
            List<SupplierDeliveryStats> stats,
            Map<UUID, int[]> fillRates) {

        List<SupplierPerformanceEntry> entries = new ArrayList<>();
        for (SupplierDeliveryStats s : stats) {
            String name = names.getOrDefault(s.supplierId(), "Unknown");
            int[] fr = fillRates.getOrDefault(s.supplierId(), new int[]{0, 1});
            int completed = fr[0];
            int total = fr[1];
            BigDecimal fillRate = total == 0
                    ? BigDecimal.ZERO
                    : BigDecimal.valueOf(completed).divide(BigDecimal.valueOf(total), 4, RoundingMode.HALF_UP);
            int shipments = s.earlyCount() + s.onTimeCount() + s.lateCount();
            BigDecimal otdRate = shipments == 0
                    ? BigDecimal.ZERO
                    : BigDecimal.valueOf(s.earlyCount() + s.onTimeCount())
                            .divide(BigDecimal.valueOf(shipments), 4, RoundingMode.HALF_UP);
            entries.add(new SupplierPerformanceEntry(
                    s.supplierId(), name, otdRate, fillRate,
                    s.earlyCount(), s.onTimeCount(), s.lateCount(), s.lateCount()
            ));
        }
        entries.sort(Comparator.comparing(SupplierPerformanceEntry::otdRate).reversed());
        return entries;
    }
}
