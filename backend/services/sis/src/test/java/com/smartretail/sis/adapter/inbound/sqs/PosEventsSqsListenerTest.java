package com.smartretail.sis.adapter.inbound.sqs;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.smartretail.sis.domain.model.IngestionResult;
import com.smartretail.sis.domain.model.SalesTransaction;
import com.smartretail.sis.port.inbound.SalesEventPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PosEventsSqsListenerTest {

    @Mock
    private SalesEventPort salesEventPort;

    private PosEventsSqsListener listener;

    @BeforeEach
    void setUp() {
        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        listener = new PosEventsSqsListener(salesEventPort, mapper);
    }

    @Test
    void onPosEvent_validMessage_callsPort() throws Exception {
        UUID txId = UUID.randomUUID();
        String raw = """
                {
                  "transactionId": "%s",
                  "storeId": "STORE-001",
                  "skuId": "SKU-BEV-001",
                  "dcId": "DC-LONDON",
                  "quantity": 30,
                  "unitPrice": 8.50,
                  "channel": "POS",
                  "eventTimestamp": "2026-05-15T14:23:00Z"
                }
                """.formatted(txId);

        when(salesEventPort.ingest(any())).thenReturn(new IngestionResult.Accepted(txId));

        listener.onPosEvent(raw);

        ArgumentCaptor<SalesTransaction> captor = ArgumentCaptor.forClass(SalesTransaction.class);
        verify(salesEventPort).ingest(captor.capture());

        assertThat(captor.getValue().skuId()).isEqualTo("SKU-BEV-001");
        assertThat(captor.getValue().dcId()).isEqualTo("DC-LONDON");
        assertThat(captor.getValue().quantity()).isEqualTo(30);
        assertThat(captor.getValue().channel()).isEqualTo(SalesTransaction.Channel.POS);
    }

    @Test
    void onPosEvent_duplicateEvent_logsWarningAndCompletes() throws Exception {
        UUID txId = UUID.randomUUID();
        String raw = """
                {
                  "transactionId": "%s",
                  "storeId": "STORE-001",
                  "skuId": "SKU-BEV-001",
                  "dcId": "DC-LONDON",
                  "quantity": 10,
                  "unitPrice": 5.00,
                  "channel": "POS",
                  "eventTimestamp": "2026-05-15T14:23:00Z"
                }
                """.formatted(txId);

        when(salesEventPort.ingest(any())).thenReturn(new IngestionResult.Duplicate(txId));

        listener.onPosEvent(raw);  // Should not throw

        verify(salesEventPort).ingest(any());
    }

    @Test
    void onPosEvent_malformedJson_throwsRuntimeException() {
        assertThatThrownBy(() -> listener.onPosEvent("not-valid-json"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("deserialisation failed");
    }

    @Test
    void onPosEvent_portThrows_propagatesException() {
        UUID txId = UUID.randomUUID();
        String raw = """
                {
                  "transactionId": "%s",
                  "storeId": "STORE-001",
                  "skuId": "SKU-BEV-001",
                  "dcId": "DC-LONDON",
                  "quantity": 10,
                  "unitPrice": 5.00,
                  "channel": "ECOMMERCE",
                  "eventTimestamp": "2026-05-15T14:23:00Z"
                }
                """.formatted(txId);

        doThrow(new RuntimeException("port error")).when(salesEventPort).ingest(any());

        assertThatThrownBy(() -> listener.onPosEvent(raw))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("POS SQS processing failed");
    }
}
