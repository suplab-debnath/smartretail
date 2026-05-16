package com.smartretail.lambda.kinesis;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@JsonIgnoreProperties(ignoreUnknown = true)
public record PosEventPayload(
        UUID transactionId,
        String storeId,
        String skuId,
        String dcId,
        int quantity,
        BigDecimal unitPrice,
        String channel,
        Instant eventTimestamp
) {
    public PosEventPayload {
        if (transactionId == null) throw new IllegalArgumentException("transactionId must not be null");
        if (skuId == null || skuId.isBlank()) throw new IllegalArgumentException("skuId must not be blank");
        if (quantity <= 0) throw new IllegalArgumentException("quantity must be > 0");
    }
}
