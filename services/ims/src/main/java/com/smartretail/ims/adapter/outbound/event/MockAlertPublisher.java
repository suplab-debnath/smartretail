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
 * Local-only publisher that delivers InventoryAlertEvents directly to RE via HTTP,
 * bypassing EventBridge and SQS. Envelope format matches EventBridge→SQS delivery.
 */
@Component
@Profile("local")
public class MockAlertPublisher implements AlertPublisherPort {

    private static final Logger log = LoggerFactory.getLogger(MockAlertPublisher.class);

    private final String reUrl;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public MockAlertPublisher(
            @Value("${smartretail.mock.re-url:http://localhost:8082}") String reUrl) {
        this.reUrl = reUrl;
        this.objectMapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    @Override
    public void publishInventoryAlertEvent(StockAlert alert, InventoryPosition position) {
        try {
            Map<String, Object> detail = Map.of(
                    "alertId",        alert.getAlertId().toString(),
                    "positionId",     alert.getPositionId().toString(),
                    "skuId",          alert.getSkuId(),
                    "dcId",           alert.getDcId(),
                    "alertType",      alert.getAlertType().name(),
                    "severity",       alert.getSeverity().name(),
                    "thresholdValue", alert.getThresholdValue(),
                    "actualValue",    alert.getActualValue(),
                    "status",         alert.getStatus()
            );

            Map<String, Object> envelope = new LinkedHashMap<>();
            envelope.put("source",      "smartretail.ims");
            envelope.put("detail-type", "InventoryAlertEvent");
            envelope.put("detail",      detail);
            envelope.put("time",        Instant.now().toString());

            String body = objectMapper.writeValueAsString(envelope);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(reUrl + "/internal/mock/alert-event"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .timeout(Duration.ofSeconds(10))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 400) {
                log.warn("Mock RE delivery returned {}: {}", response.statusCode(), response.body());
            } else {
                log.info("MockAlertPublisher → RE: alertId={} skuId={} severity={}",
                        alert.getAlertId(), alert.getSkuId(), alert.getSeverity());
            }
        } catch (Exception e) {
            log.warn("Mock RE delivery failed (RE may not be running yet): {}", e.getMessage());
        }
    }
}
