package com.smartretail.re.domain.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * DTO representing the detail payload of an InventoryAlertEvent published by IMS.
 * Deserialized from the EventBridge envelope's "detail" field.
 *
 * thresholdValue = reorderPoint at the time the alert was raised
 * actualValue    = ATP (onHand - reserved) at the time the alert was raised
 *
 * Used by ProcessInventoryAlertUseCase to compute:
 *   quantity = max(thresholdValue - actualValue, moq)
 * without any cross-schema join to the inventory schema.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record InventoryAlertEventDto(
        @JsonProperty("alertId")        String alertId,
        @JsonProperty("positionId")     String positionId,
        @JsonProperty("skuId")          String skuId,
        @JsonProperty("dcId")           String dcId,
        @JsonProperty("alertType")      String alertType,
        @JsonProperty("severity")       String severity,
        @JsonProperty("thresholdValue") int thresholdValue,
        @JsonProperty("actualValue")    int actualValue,
        @JsonProperty("status")         String status
) {}
