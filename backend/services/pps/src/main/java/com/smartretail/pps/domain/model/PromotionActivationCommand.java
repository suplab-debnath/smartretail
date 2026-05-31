package com.smartretail.pps.domain.model;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Immutable command carrying the data from a PromotionActivated external event.
 * Zero AWS imports — lives in the domain core.
 */
public record PromotionActivationCommand(
        UUID promotionId,
        String promotionName,
        List<String> skuIds,
        BigDecimal discountPct,
        LocalDate validFrom,
        LocalDate validTo
) {}
