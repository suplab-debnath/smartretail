package com.smartretail.re.adapter.outbound.event;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.smartretail.re.domain.model.PurchaseOrder;
import com.smartretail.re.port.outbound.PurchaseOrderEventPublisherPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.eventbridge.EventBridgeClient;
import software.amazon.awssdk.services.eventbridge.model.PutEventsRequest;
import software.amazon.awssdk.services.eventbridge.model.PutEventsRequestEntry;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

@Component
public class EventBridgePurchaseOrderPublisher implements PurchaseOrderEventPublisherPort {

    private static final Logger log = LoggerFactory.getLogger(EventBridgePurchaseOrderPublisher.class);
    private static final String EVENT_SOURCE = "smartretail.re";
    private static final String DETAIL_TYPE  = "PurchaseOrderEvent";

    /** Functional interface seam — allows tests to inject without mocking AWS SDK client. */
    @FunctionalInterface
    interface PutEventsExecutor {
        software.amazon.awssdk.services.eventbridge.model.PutEventsResponse execute(PutEventsRequest request);
    }

    private final PutEventsExecutor executor;
    private final String busName;
    private final ObjectMapper objectMapper;

    /** Production constructor — delegates to real EventBridgeClient. */
    @Autowired
    public EventBridgePurchaseOrderPublisher(
            EventBridgeClient eventBridge,
            @Value("${smartretail.eventbridge.bus-name}") String busName) {
        this(eventBridge::putEvents, busName);
    }

    /** Test constructor — accepts injected executor. */
    EventBridgePurchaseOrderPublisher(PutEventsExecutor executor, String busName) {
        this.executor = executor;
        this.busName = busName;
        this.objectMapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    @Override
    public void publishPurchaseOrderEvent(PurchaseOrder po) {
        try {
            // Use HashMap to allow null values (Map.of() does not permit nulls)
            Map<String, Object> detail = new HashMap<>();
            detail.put("poId",           po.getPoId().toString());
            detail.put("ruleId",         po.getRuleId().toString());
            detail.put("supplierId",     po.getSupplierId());
            detail.put("skuId",          po.getSkuId());
            detail.put("dcId",           po.getDcId());
            detail.put("quantity",       po.getQuantity());
            detail.put("totalValue",     po.getTotalValue());
            detail.put("workflowStatus", po.getWorkflowStatus().name());
            detail.put("alertId",        po.getAlertId() != null ? po.getAlertId().toString() : null);
            detail.put("createdAt",      po.getCreatedAt());

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
                throw new RuntimeException(
                        "EventBridge rejected PurchaseOrderEvent for poId=" + po.getPoId());
            }

            log.info("PurchaseOrderEvent published to EventBridge: poId={} status={} skuId={} dcId={}",
                    po.getPoId(), po.getWorkflowStatus(), po.getSkuId(), po.getDcId());

        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Failed to publish PurchaseOrderEvent for poId=" + po.getPoId(), e);
        }
    }
}
