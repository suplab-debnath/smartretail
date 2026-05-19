package com.smartretail.ims.adapter.inbound.mock;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartretail.ims.domain.model.SalesTransactionEventDto;
import com.smartretail.ims.port.inbound.InventoryUpdatePort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Local-only inbound adapter that receives SalesTransactionEvents forwarded directly by SIS
 * via HTTP, replacing the SQS listener. Accepts the same EventBridge envelope JSON format.
 */
@RestController
@Profile("local")
@RequestMapping("/internal/mock")
public class MockSalesEventController {

    private static final Logger log = LoggerFactory.getLogger(MockSalesEventController.class);

    private final InventoryUpdatePort inventoryUpdatePort;
    private final ObjectMapper objectMapper;

    public MockSalesEventController(InventoryUpdatePort inventoryUpdatePort, ObjectMapper objectMapper) {
        this.inventoryUpdatePort = inventoryUpdatePort;
        this.objectMapper = objectMapper;
    }

    @PostMapping("/sales-event")
    public ResponseEntity<Void> receive(@RequestBody String rawMessage) {
        String traceId = UUID.randomUUID().toString();
        MDC.put("traceId", traceId);
        MDC.put("service", "IMS");
        MDC.put("eventType", "SalesTransactionEvent");
        try {
            EventBridgeEnvelope envelope = objectMapper.readValue(rawMessage, EventBridgeEnvelope.class);
            SalesTransactionEventDto event = objectMapper.convertValue(
                    envelope.detail(), SalesTransactionEventDto.class);

            MDC.put("skuId", event.skuId());
            MDC.put("dcId",  event.dcId());

            inventoryUpdatePort.processSalesEvent(event);
            return ResponseEntity.ok().build();
        } catch (Exception e) {
            log.error("Failed to process mock sales event", e);
            return ResponseEntity.internalServerError().build();
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
