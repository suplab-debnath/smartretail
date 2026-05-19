package com.smartretail.re.adapter.inbound.mock;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartretail.re.domain.model.InventoryAlertEventDto;
import com.smartretail.re.port.inbound.ProcessInventoryAlertPort;
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
 * Local-only inbound adapter that receives InventoryAlertEvents forwarded directly by IMS
 * via HTTP, replacing the SQS FIFO listener. Accepts the same EventBridge envelope JSON format.
 */
@RestController
@Profile("local")
@RequestMapping("/internal/mock")
public class MockAlertEventController {

    private static final Logger log = LoggerFactory.getLogger(MockAlertEventController.class);

    private final ProcessInventoryAlertPort processInventoryAlertPort;
    private final ObjectMapper objectMapper;

    public MockAlertEventController(ProcessInventoryAlertPort processInventoryAlertPort,
                                    ObjectMapper objectMapper) {
        this.processInventoryAlertPort = processInventoryAlertPort;
        this.objectMapper = objectMapper;
    }

    @PostMapping("/alert-event")
    public ResponseEntity<Void> receive(@RequestBody String rawMessage) {
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
            return ResponseEntity.ok().build();
        } catch (Exception e) {
            log.error("Failed to process mock alert event", e);
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
