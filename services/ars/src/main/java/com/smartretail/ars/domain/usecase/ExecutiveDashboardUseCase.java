package com.smartretail.ars.domain.usecase;

import com.smartretail.ars.domain.model.ExecutiveDashboard;
import com.smartretail.ars.domain.model.ExecutiveDashboard.*;
import com.smartretail.ars.port.inbound.ExecutiveDashboardPort;
import com.smartretail.ars.port.outbound.ForecastReadPort;
import com.smartretail.ars.port.outbound.InventoryReadPort;
import com.smartretail.ars.port.outbound.ReplenishmentReadPort;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

@Service
public class ExecutiveDashboardUseCase implements ExecutiveDashboardPort {

    private static final int HISTORY_LIMIT = 30;
    private static final int TREND_WINDOW_DAYS = 7;
    private static final BigDecimal TREND_THRESHOLD = BigDecimal.valueOf(0.005);

    private final ForecastReadPort forecastReadPort;
    private final InventoryReadPort inventoryReadPort;
    private final ReplenishmentReadPort replenishmentReadPort;

    public ExecutiveDashboardUseCase(
            ForecastReadPort forecastReadPort,
            InventoryReadPort inventoryReadPort,
            ReplenishmentReadPort replenishmentReadPort) {
        this.forecastReadPort = forecastReadPort;
        this.inventoryReadPort = inventoryReadPort;
        this.replenishmentReadPort = replenishmentReadPort;
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

        CompletableFuture.allOf(forecastFuture, stockoutFuture, cycleTimeFuture,
                stockoutHistoryFuture, cycleHistoryFuture).join();

        List<MapeDataPoint> history = forecastFuture.join();
        int[] stockoutCounts = stockoutFuture.join();
        Optional<BigDecimal> cycleTime = cycleTimeFuture.join();
        List<StockoutDataPoint> stockoutHistory = stockoutHistoryFuture.join();
        List<CycleTimeDataPoint> cycleHistory = cycleHistoryFuture.join();

        return new ExecutiveDashboard(
                buildForecastAccuracy(history),
                buildStockoutFrequency(stockoutCounts, stockoutHistory),
                buildCycleTime(cycleTime, cycleHistory),
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
}
