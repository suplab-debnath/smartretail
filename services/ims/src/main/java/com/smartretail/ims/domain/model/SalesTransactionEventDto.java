package com.smartretail.ims.domain.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Detail payload extracted from the EventBridge SalesTransactionEvent envelope.
 * Passed in via the IMS SQS queue (EventBridge rule: SalesTransactionEvent → ims-sales SQS).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record SalesTransactionEventDto(
        @JsonProperty("transactionId") UUID transactionId,
        @JsonProperty("storeId") String storeId,
        @JsonProperty("skuId") String skuId,
        @JsonProperty("dcId") String dcId,
        @JsonProperty("quantity") int quantity,
        @JsonProperty("unitPrice") BigDecimal unitPrice,
        @JsonProperty("channel") String channel,
        @JsonProperty("eventTimestamp") Instant eventTimestamp
) {}
