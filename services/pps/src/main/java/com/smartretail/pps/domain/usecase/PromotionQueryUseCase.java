package com.smartretail.pps.domain.usecase;

import com.smartretail.pps.domain.model.PromotionList;
import com.smartretail.pps.domain.model.PromotionList.PromotionSchedule;
import com.smartretail.pps.port.inbound.PromotionQueryPort;
import com.smartretail.pps.port.outbound.PromotionReadPort;
import com.smartretail.pps.port.outbound.PromotionReadPort.PromotionRow;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

/**
 * Assembles the promotion schedule list with data freshness.
 * Single-schema reads only (promotions schema via PromotionReadPort).
 */
@Service
public class PromotionQueryUseCase implements PromotionQueryPort {

    private final PromotionReadPort promotionReadPort;

    public PromotionQueryUseCase(PromotionReadPort promotionReadPort) {
        this.promotionReadPort = promotionReadPort;
    }

    @Override
    public PromotionList getPromotionSchedules(String status) {
        List<PromotionRow> rows = promotionReadPort.findPromotionSchedules(status);
        Instant freshness = promotionReadPort.findDataFreshness();

        List<PromotionSchedule> schedules = rows.stream()
                .map(r -> new PromotionSchedule(
                        r.promotionId(),
                        r.promotionName(),
                        r.skuIds(),
                        r.dcIds(),
                        r.discountPct(),
                        r.upliftFactor(),
                        r.elasticityCoeff(),
                        r.validFrom(),
                        r.validTo(),
                        r.status(),
                        r.sourceEventId()))
                .toList();

        return new PromotionList(schedules, freshness);
    }
}
