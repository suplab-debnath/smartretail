package com.smartretail.re.adapter.inbound.sqs;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartretail.re.domain.model.InventoryAlertEventDto;
import com.smartretail.re.port.inbound.ProcessInventoryAlertPort;
import io.awspring.cloud.sqs.annotation.SqsListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class AlertSqsListener {

    private static final Logger log = LoggerFactory.getLogger(AlertSqsListener.class);

    private final ProcessInventoryAlertPort processInventoryAlertPort;
    private final ObjectMapper objectMapper;

    public AlertSqsListener(ProcessInventoryAlertPort processInventoryAlertPort,
                            ObjectMapper objectMapper) {
        this.processInventoryAlertPort = processInventoryAlertPort;
        this.objectMapper = objectMapper;
    }

    /**
     * Receives EventBridge InventoryAlertEvent from the re-alert FIFO SQS queue.
     * EventBridge wraps the payload in an outer envelope; we extract the "detail" field.
     * Messages are grouped by dcId (FIFO message group), ensuring per-DC ordering.
     */
    @SqsListener("${smartretail.sqs.re-alert-queue-url}")
    public void onAlertEvent(String rawMessage) {
        String traceId = UUID.randomUUID().toString();
        MDC.put("traceId", traceId);
        MDC.put("service", "RE");
        MDC.put("eventType", "InventoryAlertEvent");
        try {
            EventBridgeEnvelope envelope = objectMapper.readValue(rawMessage, EventBridgeEnvelope.class);
            InventoryAlertEventDto event = objectMapper.convertValue(
                    envelope.detail(), InventoryAlertEventDto.class);

            MDC.put("skuId", event.skuId());
            MDC.put("dcId",  event.dcId());

            processInventoryAlertPort.processInventoryAlert(event);
        } catch (Exception e) {
            log.error("Failed to process InventoryAlertEvent — will retry or DLQ", e);
            throw new RuntimeException("SQS processing failed", e);
        } finally {
            MDC.clear();
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record EventBridgeEnvelope(
            @JsonProperty("source")      String source,
            @JsonProperty("detail-type") String detailType,
            @JsonProperty("detail")      Object detail
    ) {}
}
