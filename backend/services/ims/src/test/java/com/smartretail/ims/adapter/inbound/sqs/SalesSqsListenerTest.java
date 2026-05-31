package com.smartretail.ims.adapter.inbound.sqs;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.smartretail.ims.domain.model.SalesTransactionEventDto;
import com.smartretail.ims.port.inbound.InventoryUpdatePort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SalesSqsListenerTest {

    @Mock private InventoryUpdatePort inventoryUpdatePort;

    private SalesSqsListener listener;

    @BeforeEach
    void setUp() {
        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        listener = new SalesSqsListener(inventoryUpdatePort, mapper);
    }

    @Test
    void onSalesEvent_validEnvelope_callsPort() throws Exception {
        UUID txId = UUID.randomUUID();
        String raw = """
            {
              "source": "smartretail.sis",
              "detail-type": "SalesTransactionEvent",
              "detail": {
                "transactionId": "%s",
                "storeId": "STORE-001",
                "skuId": "SKU-BEV-001",
                "dcId": "DC-LONDON",
                "quantity": 30,
                "price": 8.50,
                "channel": "POS",
                "transactionTimestamp": "2026-05-15T14:23:00Z"
              }
            }
            """.formatted(txId);

        listener.onSalesEvent(raw);

        ArgumentCaptor<SalesTransactionEventDto> captor = ArgumentCaptor.forClass(SalesTransactionEventDto.class);
        verify(inventoryUpdatePort).processSalesEvent(captor.capture());
        assertThat(captor.getValue().skuId()).isEqualTo("SKU-BEV-001");
        assertThat(captor.getValue().dcId()).isEqualTo("DC-LONDON");
        assertThat(captor.getValue().quantity()).isEqualTo(30);
    }

    @Test
    void onSalesEvent_malformedJson_throwsRuntimeException() {
        assertThatThrownBy(() -> listener.onSalesEvent("not-valid-json"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("SQS processing failed");
    }

    @Test
    void onSalesEvent_portThrows_propagatesException() {
        String raw = """
            {
              "source": "smartretail.sis",
              "detail-type": "SalesTransactionEvent",
              "detail": {
                "transactionId": "%s",
                "storeId": "STORE-001",
                "skuId": "SKU-BEV-001",
                "dcId": "DC-LONDON",
                "quantity": 10,
                "price": 5.00,
                "channel": "POS",
                "transactionTimestamp": "2026-05-15T14:23:00Z"
              }
            }
            """.formatted(UUID.randomUUID());

        doThrow(new RuntimeException("port error")).when(inventoryUpdatePort).processSalesEvent(any());

        assertThatThrownBy(() -> listener.onSalesEvent(raw))
                .isInstanceOf(RuntimeException.class);
    }
}
