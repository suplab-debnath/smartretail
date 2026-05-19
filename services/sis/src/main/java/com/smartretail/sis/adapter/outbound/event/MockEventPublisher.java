package com.smartretail.sis.adapter.outbound.event;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.smartretail.sis.domain.model.SalesTransaction;
import com.smartretail.sis.port.outbound.EventPublisherPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Local-only publisher that delivers SalesTransactionEvents directly to IMS via HTTP,
 * bypassing EventBridge and SQS. The envelope format matches what EventBridge→SQS delivers
 * so the IMS mock controller can reuse the same deserialization logic.
 */
@Component
@Profile("local")
public class MockEventPublisher implements EventPublisherPort {

    private static final Logger log = LoggerFactory.getLogger(MockEventPublisher.class);

    private final String imsUrl;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public MockEventPublisher(
            @Value("${smartretail.mock.ims-url:http://localhost:8081}") String imsUrl) {
        this.imsUrl = imsUrl;
        this.objectMapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    @Override
    public void publishSalesTransactionEvent(SalesTransaction transaction) {
        try {
            Map<String, Object> detail = Map.of(
                    "transactionId",  transaction.transactionId().toString(),
                    "storeId",        transaction.storeId(),
                    "skuId",          transaction.skuId(),
                    "dcId",           transaction.dcId(),
                    "quantity",       transaction.quantity(),
                    "unitPrice",      transaction.unitPrice(),
                    "channel",        transaction.channel().name(),
                    "eventTimestamp", transaction.eventTimestamp().toString()
            );

            Map<String, Object> envelope = new LinkedHashMap<>();
            envelope.put("source",      "smartretail.sis");
            envelope.put("detail-type", "SalesTransactionEvent");
            envelope.put("detail",      detail);
            envelope.put("time",        Instant.now().toString());

            String body = objectMapper.writeValueAsString(envelope);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(imsUrl + "/internal/mock/sales-event"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .timeout(Duration.ofSeconds(10))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 400) {
                log.warn("Mock IMS delivery returned {}: {}", response.statusCode(), response.body());
            } else {
                log.info("MockEventPublisher → IMS: transactionId={}", transaction.transactionId());
            }
        } catch (Exception e) {
            log.warn("Mock IMS delivery failed (IMS may not be running yet): {}", e.getMessage());
        }
    }
}
