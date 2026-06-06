package com.smartretail.re.adapter.outbound.event;

import com.smartretail.re.domain.model.PurchaseOrder;
import com.smartretail.re.domain.model.WorkflowStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.services.eventbridge.model.PutEventsRequest;
import software.amazon.awssdk.services.eventbridge.model.PutEventsResponse;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EventBridgePurchaseOrderPublisherTest {

    @Mock
    private EventBridgePurchaseOrderPublisher.PutEventsExecutor executor;

    private EventBridgePurchaseOrderPublisher publisher;

    @BeforeEach
    void setUp() {
        publisher = new EventBridgePurchaseOrderPublisher(executor, "test-bus");
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private PurchaseOrder samplePo(UUID alertId) {
        PurchaseOrder po = new PurchaseOrder();
        po.setPoId(UUID.randomUUID());
        po.setRuleId(UUID.randomUUID());
        po.setSupplierId("supplier-1");
        po.setSkuId("SKU-BEV-001");
        po.setDcId("DC-LONDON");
        po.setQuantity(100);
        po.setTotalValue(new BigDecimal("850.00"));
        po.setWorkflowStatus(WorkflowStatus.PENDING_APPROVAL);
        po.setAlertId(alertId);
        po.setCreatedAt(Instant.parse("2026-05-30T10:00:00Z"));
        return po;
    }

    private PutEventsResponse successResponse() {
        return PutEventsResponse.builder().failedEntryCount(0).build();
    }

    private PutEventsResponse failedResponse() {
        return PutEventsResponse.builder().failedEntryCount(1).build();
    }

    // ── Tests ──────────────────────────────────────────────────────────────────

    @Test
    void publishPurchaseOrderEvent_withAlertId_sendsCorrectPayload() {
        UUID alertId = UUID.randomUUID();
        PurchaseOrder po = samplePo(alertId);
        when(executor.execute(any(PutEventsRequest.class))).thenReturn(successResponse());

        publisher.publishPurchaseOrderEvent(po);

        ArgumentCaptor<PutEventsRequest> captor = ArgumentCaptor.forClass(PutEventsRequest.class);
        verify(executor).execute(captor.capture());

        var entry = captor.getValue().entries().get(0);
        assertThat(entry.eventBusName()).isEqualTo("test-bus");
        assertThat(entry.source()).isEqualTo("smartretail.re");
        assertThat(entry.detailType()).isEqualTo("PurchaseOrderEvent");
        assertThat(entry.detail())
                .contains(po.getPoId().toString())
                .contains(alertId.toString())
                .contains("PENDING_APPROVAL");
    }

    @Test
    void publishPurchaseOrderEvent_withNullAlertId_serialisesAlertIdAsNull() {
        PurchaseOrder po = samplePo(null);
        when(executor.execute(any(PutEventsRequest.class))).thenReturn(successResponse());

        publisher.publishPurchaseOrderEvent(po);

        ArgumentCaptor<PutEventsRequest> captor = ArgumentCaptor.forClass(PutEventsRequest.class);
        verify(executor).execute(captor.capture());

        String detail = captor.getValue().entries().get(0).detail();
        assertThat(detail).contains(po.getPoId().toString());
        assertThat(detail).contains("alertId");
    }

    @Test
    void publishPurchaseOrderEvent_whenEventBridgeRejectEntry_throwsRuntimeException() {
        PurchaseOrder po = samplePo(UUID.randomUUID());
        when(executor.execute(any(PutEventsRequest.class))).thenReturn(failedResponse());

        assertThatThrownBy(() -> publisher.publishPurchaseOrderEvent(po))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("EventBridge rejected PurchaseOrderEvent for poId=")
                .hasMessageContaining(po.getPoId().toString());
    }

    @Test
    void publishPurchaseOrderEvent_whenExecutorThrowsRuntimeException_rethrows() {
        PurchaseOrder po = samplePo(null);
        when(executor.execute(any(PutEventsRequest.class)))
                .thenThrow(new RuntimeException("connection refused"));

        assertThatThrownBy(() -> publisher.publishPurchaseOrderEvent(po))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("connection refused");
    }
}
