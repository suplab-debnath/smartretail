package com.smartretail.sis.adapter.outbound.event;

import com.smartretail.sis.domain.model.SalesTransaction;
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
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class EventBridgePublisherTest {

    @Mock
    private EventBridgePublisher.PutEventsExecutor executor;

    private EventBridgePublisher publisher;
    private SalesTransaction transaction;

    @BeforeEach
    void setUp() {
        publisher = new EventBridgePublisher(executor, "smartretail-events-test");
        transaction = new SalesTransaction(
                UUID.randomUUID(), "STORE-001", "SKU-BEV-001", "DC-LONDON",
                30, BigDecimal.valueOf(8.50),
                SalesTransaction.Channel.POS, Instant.now());
    }

    @Test
    void publishSalesTransactionEvent_success_callsExecutorWithCorrectEntry() {
        PutEventsResponse response = PutEventsResponse.builder().failedEntryCount(0).build();
        when(executor.execute(any())).thenReturn(response);

        publisher.publishSalesTransactionEvent(transaction);

        ArgumentCaptor<PutEventsRequest> captor = ArgumentCaptor.forClass(PutEventsRequest.class);
        verify(executor).execute(captor.capture());

        var entry = captor.getValue().entries().get(0);
        assertThat(entry.eventBusName()).isEqualTo("smartretail-events-test");
        assertThat(entry.source()).isEqualTo("smartretail.sis");
        assertThat(entry.detailType()).isEqualTo("SalesTransactionEvent");
        assertThat(entry.detail()).contains("SKU-BEV-001");
        assertThat(entry.detail()).contains("DC-LONDON");
        assertThat(entry.detail()).contains("STORE-001");
    }

    @Test
    void publishSalesTransactionEvent_failedEntry_throwsRuntimeException() {
        PutEventsResponse failResponse = PutEventsResponse.builder().failedEntryCount(1).build();
        when(executor.execute(any())).thenReturn(failResponse);

        assertThatThrownBy(() -> publisher.publishSalesTransactionEvent(transaction))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("EventBridge rejected entry");
    }

    @Test
    void publishSalesTransactionEvent_executorThrowsRuntimeException_propagatesDirectly() {
        when(executor.execute(any())).thenThrow(new IllegalStateException("network error"));

        assertThatThrownBy(() -> publisher.publishSalesTransactionEvent(transaction))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("network error");
    }

    @Test
    void publishSalesTransactionEvent_detailContainsTransactionId() {
        PutEventsResponse response = PutEventsResponse.builder().failedEntryCount(0).build();
        when(executor.execute(any())).thenReturn(response);

        publisher.publishSalesTransactionEvent(transaction);

        ArgumentCaptor<PutEventsRequest> captor = ArgumentCaptor.forClass(PutEventsRequest.class);
        verify(executor).execute(captor.capture());

        String detail = captor.getValue().entries().get(0).detail();
        assertThat(detail).contains(transaction.transactionId().toString());
        assertThat(detail).contains("POS");
    }
}
