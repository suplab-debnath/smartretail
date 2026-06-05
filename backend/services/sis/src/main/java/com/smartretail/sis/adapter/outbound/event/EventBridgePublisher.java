package com.smartretail.sis.adapter.outbound.event;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.smartretail.sis.domain.model.SalesTransaction;
import com.smartretail.sis.port.outbound.EventPublisherPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.eventbridge.EventBridgeClient;
import software.amazon.awssdk.services.eventbridge.model.PutEventsRequest;
import software.amazon.awssdk.services.eventbridge.model.PutEventsRequestEntry;

import java.time.Instant;
import java.util.Map;

@Component
public class EventBridgePublisher implements EventPublisherPort {

    private static final Logger log = LoggerFactory.getLogger(EventBridgePublisher.class);
    private static final String EVENT_SOURCE = "smartretail.sis";
    private static final String DETAIL_TYPE = "SalesTransactionEvent";

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
    public EventBridgePublisher(
            EventBridgeClient eventBridge,
            @Value("${smartretail.eventbridge.bus-name}") String busName) {
        this(eventBridge::putEvents, busName);
    }

    /** Test constructor — accepts injected executor. */
    EventBridgePublisher(PutEventsExecutor executor, String busName) {
        this.executor = executor;
        this.busName = busName;
        this.objectMapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    @Override
    public void publishSalesTransactionEvent(SalesTransaction transaction) {
        try {
            Map<String, Object> detail = Map.of(
                    "transactionId", transaction.transactionId().toString(),
                    "storeId", transaction.storeId(),
                    "skuId", transaction.skuId(),
                    "dcId", transaction.dcId(),
                    "quantity", transaction.quantity(),
                    "unitPrice", transaction.unitPrice(),
                    "channel", transaction.channel().name(),
                    "eventTimestamp", transaction.eventTimestamp().toString()
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
                throw new RuntimeException("EventBridge rejected entry for transactionId=" + transaction.transactionId());
            }

            log.info("SalesTransactionEvent published: transactionId={}", transaction.transactionId());
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Failed to publish SalesTransactionEvent", e);
        }
    }
}
