package com.smartretail.sis.domain.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record SalesTransaction(
        UUID transactionId,
        String storeId,
        String skuId,
        String dcId,
        int quantity,
        BigDecimal unitPrice,
        Channel channel,
        Instant eventTimestamp
) {
    public SalesTransaction {
        Objects.requireNonNull(transactionId, "transactionId must not be null");
        Objects.requireNonNull(storeId, "storeId must not be null");
        Objects.requireNonNull(skuId, "skuId must not be null");
        Objects.requireNonNull(dcId, "dcId must not be null");
        Objects.requireNonNull(unitPrice, "unitPrice must not be null");
        Objects.requireNonNull(channel, "channel must not be null");
        Objects.requireNonNull(eventTimestamp, "eventTimestamp must not be null");
        if (quantity <= 0) throw new IllegalArgumentException("quantity must be > 0");
        if (unitPrice.compareTo(BigDecimal.ZERO) < 0) throw new IllegalArgumentException("unitPrice must be >= 0");
    }

    public enum Channel { POS, ECOMMERCE }
}
