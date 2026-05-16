package com.smartretail.ims.adapter.inbound.sqs;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartretail.ims.domain.model.SalesTransactionEventDto;
import com.smartretail.ims.port.inbound.InventoryUpdatePort;
import io.awspring.cloud.sqs.annotation.SqsListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class SalesSqsListener {

    private static final Logger log = LoggerFactory.getLogger(SalesSqsListener.class);

    private final InventoryUpdatePort inventoryUpdatePort;
    private final ObjectMapper objectMapper;

    public SalesSqsListener(InventoryUpdatePort inventoryUpdatePort, ObjectMapper objectMapper) {
        this.inventoryUpdatePort = inventoryUpdatePort;
        this.objectMapper = objectMapper;
    }

    /**
     * Receives EventBridge SalesTransactionEvent from the ims-sales SQS queue.
     * EventBridge wraps the payload in an outer envelope; we extract the "detail" field.
     */
    @SqsListener("${smartretail.sqs.ims-sales-queue-url}")
    public void onSalesEvent(String rawMessage) {
        String traceId = UUID.randomUUID().toString();
        MDC.put("traceId", traceId);
        MDC.put("service", "IMS");
        MDC.put("eventType", "SalesTransactionEvent");
        try {
            EventBridgeEnvelope envelope = objectMapper.readValue(rawMessage, EventBridgeEnvelope.class);
            SalesTransactionEventDto event = objectMapper.convertValue(
                    envelope.detail(), SalesTransactionEventDto.class);

            MDC.put("skuId", event.skuId());
            MDC.put("dcId", event.dcId());

            inventoryUpdatePort.processSalesEvent(event);
        } catch (Exception e) {
            log.error("Failed to process SQS message — will retry or DLQ", e);
            throw new RuntimeException("SQS processing failed", e);
        } finally {
            MDC.clear();
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record EventBridgeEnvelope(
            @JsonProperty("source") String source,
            @JsonProperty("detail-type") String detailType,
            @JsonProperty("detail") Object detail
    ) {}
}
