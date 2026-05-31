package com.smartretail.re.adapter.inbound.sqs;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartretail.re.domain.model.InventoryAlertEventDto;
import com.smartretail.re.port.inbound.ProcessInventoryAlertPort;
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
class AlertSqsListenerTest {

    @Mock
    private ProcessInventoryAlertPort processInventoryAlertPort;

    private AlertSqsListener listener;

    @BeforeEach
    void setUp() {
        ObjectMapper mapper = new ObjectMapper();
        listener = new AlertSqsListener(processInventoryAlertPort, mapper);
    }

    @Test
    void onAlertEvent_validEnvelope_callsPort() throws Exception {
        UUID alertId = UUID.randomUUID();
        UUID positionId = UUID.randomUUID();
        String raw = """
                {
                  "source": "smartretail.ims",
                  "detail-type": "InventoryAlertEvent",
                  "detail": {
                    "alertId": "%s",
                    "positionId": "%s",
                    "skuId": "SKU-BEV-001",
                    "dcId": "DC-LONDON",
                    "alertType": "LOW_STOCK",
                    "severity": "HIGH",
                    "thresholdValue": 50,
                    "actualValue": 10,
                    "status": "ACTIVE"
                  }
                }
                """.formatted(alertId, positionId);

        listener.onAlertEvent(raw);

        ArgumentCaptor<InventoryAlertEventDto> captor =
                ArgumentCaptor.forClass(InventoryAlertEventDto.class);
        verify(processInventoryAlertPort).processInventoryAlert(captor.capture());

        InventoryAlertEventDto event = captor.getValue();
        assertThat(event.alertId()).isEqualTo(alertId.toString());
        assertThat(event.skuId()).isEqualTo("SKU-BEV-001");
        assertThat(event.dcId()).isEqualTo("DC-LONDON");
        assertThat(event.severity()).isEqualTo("HIGH");
        assertThat(event.thresholdValue()).isEqualTo(50);
        assertThat(event.actualValue()).isEqualTo(10);
    }

    @Test
    void onAlertEvent_malformedJson_throwsRuntimeException() {
        assertThatThrownBy(() -> listener.onAlertEvent("not-valid-json"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("SQS processing failed");
    }

    @Test
    void onAlertEvent_portThrows_propagatesException() {
        UUID alertId = UUID.randomUUID();
        String raw = """
                {
                  "source": "smartretail.ims",
                  "detail-type": "InventoryAlertEvent",
                  "detail": {
                    "alertId": "%s",
                    "positionId": "%s",
                    "skuId": "SKU-BEV-001",
                    "dcId": "DC-PARIS",
                    "alertType": "CRITICAL_STOCK",
                    "severity": "CRITICAL",
                    "thresholdValue": 100,
                    "actualValue": 0,
                    "status": "ACTIVE"
                  }
                }
                """.formatted(alertId, UUID.randomUUID());

        doThrow(new RuntimeException("port error")).when(processInventoryAlertPort).processInventoryAlert(any());

        assertThatThrownBy(() -> listener.onAlertEvent(raw))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("SQS processing failed");
    }
}
