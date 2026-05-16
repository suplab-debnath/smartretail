package com.smartretail.ars.port.outbound;

import com.smartretail.ars.domain.model.ExecutiveDashboard.CycleTimeDataPoint;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

public interface ReplenishmentReadPort {
    Optional<BigDecimal> averageCycleTimeDays(int days);
    List<CycleTimeDataPoint> findWeeklyCycleTimeHistory(int days);
}
