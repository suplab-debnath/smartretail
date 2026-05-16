package com.smartretail.ars.port.outbound;

import com.smartretail.ars.domain.model.ExecutiveDashboard.MapeDataPoint;

import java.util.List;

public interface ForecastReadPort {
    /** Returns up to 30 COMPLETED forecast runs, newest first. */
    List<MapeDataPoint> findRecentMapeHistory(int limit);
}
