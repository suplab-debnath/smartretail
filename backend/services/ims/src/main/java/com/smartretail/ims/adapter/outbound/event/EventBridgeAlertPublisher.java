package com.smartretail.ims.adapter.outbound.event;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.smartretail.ims.domain.model.InventoryPosition;
import com.smartretail.ims.domain.model.StockAlert;
import com.smartretail.ims.port.outbound.AlertPublisherPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.eventbridge.EventBridgeClient;
import software.amazon.awssdk.services.eventbridge.model.PutEventsRequest;
import software.amazon.awssdk.services.eventbridge.model.PutEventsRequestEntry;

import java.time.Instant;
import java.util.Map;

@Component
public class EventBridgeAlertPublisher implements AlertPublisherPort {

    private static final Logger log = LoggerFactory.getLogger(EventBridgeAlertPublisher.class);
    private static final String EVENT_SOURCE = "smartretail.ims";
    private static final String DETAIL_TYPE  = "InventoryAlertEvent";

    /** Functional interface seam — allows tests to inject without mocking AWS SDK client. */
    @FunctionalInterface
    interface PutEventsExecutor {
        software.amazon.awssdk.services.eventbridge.model.PutEventsResponse execute(PutEventsRequest request);
    }

    private final PutEventsExecutor executor;
    private final String busName;
    private final ObjectMapper objectMapper;

    /** Production constructor — delegates to real EventBridgeClient. */
    public EventBridgeAlertPublisher(
            EventBridgeClient eventBridge,
            @Value("${smartretail.eventbridge.bus-name}") String busName) {
        this(eventBridge::putEvents, busName);
    }

    /** Test constructor — accepts injected executor. */
    EventBridgeAlertPublisher(PutEventsExecutor executor, String busName) {
        this.executor = executor;
        this.busName = busName;
        this.objectMapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    @Override
    public void publishInventoryAlertEvent(StockAlert alert, InventoryPosition position) {
        try {
            Map<String, Object> detail = Map.of(
                    "alertId",         alert.getAlertId().toString(),
                    "positionId",      alert.getPositionId().toString(),
                    "skuId",           alert.getSkuId(),
                    "dcId",            alert.getDcId(),
                    "alertType",       alert.getAlertType().name(),
                    "severity",        alert.getSeverity().name(),
                    "thresholdValue",  alert.getThresholdValue(),
                    "actualValue",     alert.getActualValue(),
                    "status",          alert.getStatus()
            );
            String detailJson = objectMapper.writeValueAsString(detail);

            PutEventsRequestEntry entry = PutEventsRequestEntry.builder()
                    .eventBusName(busName)
                    .source(EVENT_SOURCE)
                    .detailType(DETAIL_TYPE)
                    .detail(detailJson)
                    .time(Instant.now())
                    .build();

            var response = executor.execute(PutEventsRequest.builder()
                    .entries(entry)
                    .build());

            if (response.failedEntryCount() > 0) {
                throw new RuntimeException("EventBridge rejected InventoryAlertEvent for alertId=" + alert.getAlertId());
            }
            log.info("InventoryAlertEvent published: alertId={} skuId={} severity={}",
                    alert.getAlertId(), alert.getSkuId(), alert.getSeverity());
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Failed to publish InventoryAlertEvent", e);
        }
    }
}
