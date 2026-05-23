package com.smartretail.pps.adapter.inbound.rest;

import com.smartretail.pps.adapter.in.web.generated.model.PromotionSchedule;
import com.smartretail.pps.adapter.in.web.generated.model.PromotionScheduleListResponse;
import com.smartretail.pps.adapter.in.web.generated.model.PromotionStatus;
import com.smartretail.pps.domain.model.PromotionList;
import org.mapstruct.Mapper;

import java.time.ZoneOffset;
import java.util.List;

@Mapper(componentModel = "spring")
public interface PromotionResponseMapper {

    default PromotionScheduleListResponse toResponse(PromotionList result) {
        List<PromotionSchedule> schedules = result.schedules().stream()
                .map(s -> {
                    PromotionSchedule schedule = new PromotionSchedule(
                            s.promotionId(),
                            s.promotionName(),
                            s.skuIds(),
                            s.dcIds(),
                            s.discountPct(),
                            s.upliftFactor(),
                            s.validFrom(),
                            s.validTo(),
                            PromotionStatus.fromValue(s.status())
                    );
                    if (s.elasticityCoeff() != null) schedule.setElasticityCoeff(s.elasticityCoeff());
                    if (s.sourceEventId() != null)   schedule.setSourceEventId(s.sourceEventId());
                    return schedule;
                })
                .toList();

        return new PromotionScheduleListResponse(schedules, result.dataFreshness().atOffset(ZoneOffset.UTC));
    }
}
