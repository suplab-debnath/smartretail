package com.smartretail.ims.adapter.outbound.event;

import com.smartretail.ims.domain.model.AlertSeverity;
import com.smartretail.ims.domain.model.AlertType;
import com.smartretail.ims.domain.model.InventoryPosition;
import com.smartretail.ims.domain.model.StockAlert;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.services.eventbridge.model.PutEventsRequest;
import software.amazon.awssdk.services.eventbridge.model.PutEventsResponse;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class EventBridgeAlertPublisherTest {

    @Mock
    private EventBridgeAlertPublisher.PutEventsExecutor executor;

    private EventBridgeAlertPublisher publisher;

    private StockAlert alert;
    private InventoryPosition position;

    @BeforeEach
    void setUp() {
        publisher = new EventBridgeAlertPublisher(executor, "smartretail-events-test");

        position = new InventoryPosition();
        position.setPositionId(UUID.randomUUID());
        position.setSkuId("SKU-BEV-001");
        position.setDcId("DC-LONDON");
        position.setOnHand(10);
        position.setReserved(0);
        position.setReorderPoint(50);
        position.setSafetyStock(20);

        alert = StockAlert.create(position, AlertType.LOW_STOCK, AlertSeverity.HIGH);
    }

    @Test
    void publishInventoryAlertEvent_success_callsExecutorWithCorrectEntry() {
        PutEventsResponse response = PutEventsResponse.builder()
                .failedEntryCount(0)
                .build();
        when(executor.execute(any())).thenReturn(response);

        publisher.publishInventoryAlertEvent(alert, position);

        ArgumentCaptor<PutEventsRequest> captor = ArgumentCaptor.forClass(PutEventsRequest.class);
        verify(executor).execute(captor.capture());

        PutEventsRequest request = captor.getValue();
        assertThat(request.entries()).hasSize(1);

        var entry = request.entries().get(0);
        assertThat(entry.eventBusName()).isEqualTo("smartretail-events-test");
        assertThat(entry.source()).isEqualTo("smartretail.ims");
        assertThat(entry.detailType()).isEqualTo("InventoryAlertEvent");
        assertThat(entry.detail()).contains("SKU-BEV-001");
        assertThat(entry.detail()).contains("DC-LONDON");
        assertThat(entry.detail()).contains("HIGH");
        assertThat(entry.detail()).contains("LOW_STOCK");
    }

    @Test
    void publishInventoryAlertEvent_failedEntry_throwsRuntimeException() {
        PutEventsResponse failResponse = PutEventsResponse.builder()
                .failedEntryCount(1)
                .build();
        when(executor.execute(any())).thenReturn(failResponse);

        assertThatThrownBy(() -> publisher.publishInventoryAlertEvent(alert, position))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("EventBridge rejected InventoryAlertEvent");
    }

    @Test
    void publishInventoryAlertEvent_executorThrowsRuntimeException_propagatesDirectly() {
        // RuntimeException catch block re-throws as-is (no wrapping)
        when(executor.execute(any())).thenThrow(new IllegalStateException("connection refused"));

        assertThatThrownBy(() -> publisher.publishInventoryAlertEvent(alert, position))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("connection refused");
    }

    @Test
    void publishInventoryAlertEvent_detailJsonContainsAlertId() {
        PutEventsResponse response = PutEventsResponse.builder().failedEntryCount(0).build();
        when(executor.execute(any())).thenReturn(response);

        publisher.publishInventoryAlertEvent(alert, position);

        ArgumentCaptor<PutEventsRequest> captor = ArgumentCaptor.forClass(PutEventsRequest.class);
        verify(executor).execute(captor.capture());

        String detail = captor.getValue().entries().get(0).detail();
        assertThat(detail).contains(alert.getAlertId().toString());
        assertThat(detail).contains(alert.getPositionId().toString());
        assertThat(detail).contains("ACTIVE");
    }
}
