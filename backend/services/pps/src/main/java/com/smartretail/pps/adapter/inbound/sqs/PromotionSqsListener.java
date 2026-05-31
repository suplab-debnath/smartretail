package com.smartretail.pps.adapter.inbound.sqs;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartretail.pps.domain.model.PromotionActivationCommand;
import com.smartretail.pps.port.inbound.PromotionActivationPort;
import io.awspring.cloud.sqs.annotation.SqsListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Inbound SQS adapter — receives PromotionActivated events from the EventBridge
 * → SQS queue `smartretail-pps-inbound-{env}`.
 *
 * EventBridge wraps the Campaign Management payload in an outer envelope; we extract
 * the {@code detail} field and deserialise it into {@link PromotionActivatedPayload}.
 *
 * Active on {@code aws} profile only — local development bypasses SQS.
 */
@Component
@Profile("aws")
public class PromotionSqsListener {

    private static final Logger log = LoggerFactory.getLogger(PromotionSqsListener.class);

    private final PromotionActivationPort activationPort;
    private final ObjectMapper objectMapper;

    public PromotionSqsListener(PromotionActivationPort activationPort, ObjectMapper objectMapper) {
        this.activationPort = activationPort;
        this.objectMapper = objectMapper;
    }

    @SqsListener("${smartretail.sqs.pps-inbound-queue-url}")
    public void onPromotionActivated(String rawMessage) {
        MDC.put("service", "PPS");
        MDC.put("eventType", "PromotionActivated");
        try {
            // EventBridge envelope: { "source": "...", "detail-type": "...", "detail": { ... } }
            EventBridgeEnvelope envelope = objectMapper.readValue(rawMessage, EventBridgeEnvelope.class);
            PromotionActivatedPayload payload = objectMapper.convertValue(
                    envelope.detail(), PromotionActivatedPayload.class);

            MDC.put("promotionId", payload.promotionId().toString());

            PromotionActivationCommand command = new PromotionActivationCommand(
                    payload.promotionId(),
                    payload.promotionName(),
                    payload.skuIds(),
                    payload.discountPct(),
                    payload.validFrom(),
                    payload.validTo()
            );

            activationPort.activate(command);
            log.info("PromotionActivated processed promotionId={}", payload.promotionId());

        } catch (Exception e) {
            log.error("Failed to process PromotionActivated SQS message — will retry or DLQ", e);
            throw new RuntimeException("SQS processing failed", e);
        } finally {
            MDC.clear();
        }
    }

    // ── Inner types ──────────────────────────────────────────────────────────

    @JsonIgnoreProperties(ignoreUnknown = true)
    record EventBridgeEnvelope(
            @JsonProperty("source") String source,
            @JsonProperty("detail-type") String detailType,
            @JsonProperty("detail") Object detail
    ) {}

    /**
     * Shape of the Campaign Management PromotionActivated event payload
     * (the {@code detail} field from the EventBridge envelope).
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record PromotionActivatedPayload(
            @JsonProperty("promotionId") UUID promotionId,
            @JsonProperty("promotionName") String promotionName,
            @JsonProperty("skuIds") List<String> skuIds,
            @JsonProperty("discountPct") BigDecimal discountPct,
            @JsonProperty("validFrom") LocalDate validFrom,
            @JsonProperty("validTo") LocalDate validTo
    ) {}
}
