package com.smartretail.pps.adapter.inbound.sqs;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.smartretail.pps.domain.model.PromotionActivationCommand;
import com.smartretail.pps.port.inbound.PromotionActivationPort;
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
class PromotionSqsListenerTest {

    @Mock
    private PromotionActivationPort activationPort;

    private PromotionSqsListener listener;

    @BeforeEach
    void setUp() {
        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        listener = new PromotionSqsListener(activationPort, mapper);
    }

    @Test
    void onPromotionActivated_validEnvelope_callsPort() throws Exception {
        UUID promotionId = UUID.randomUUID();
        String raw = """
            {
              "source": "external.campaign-management",
              "detail-type": "PromotionActivated",
              "detail": {
                "promotionId": "%s",
                "promotionName": "Summer Beverages Promo",
                "skuIds": ["SKU-BEV-001", "SKU-BEV-002"],
                "discountPct": 15.0,
                "validFrom": "2026-07-01",
                "validTo": "2026-07-31"
              }
            }
            """.formatted(promotionId);

        listener.onPromotionActivated(raw);

        ArgumentCaptor<PromotionActivationCommand> captor =
                ArgumentCaptor.forClass(PromotionActivationCommand.class);
        verify(activationPort).activate(captor.capture());

        PromotionActivationCommand cmd = captor.getValue();
        assertThat(cmd.promotionId()).isEqualTo(promotionId);
        assertThat(cmd.promotionName()).isEqualTo("Summer Beverages Promo");
        assertThat(cmd.skuIds()).containsExactly("SKU-BEV-001", "SKU-BEV-002");
        assertThat(cmd.discountPct().doubleValue()).isEqualTo(15.0);
    }

    @Test
    void onPromotionActivated_malformedJson_throwsRuntimeException() {
        assertThatThrownBy(() -> listener.onPromotionActivated("not-valid-json"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("SQS processing failed");
    }

    @Test
    void onPromotionActivated_portThrows_propagatesException() {
        UUID promotionId = UUID.randomUUID();
        String raw = """
            {
              "source": "external.campaign-management",
              "detail-type": "PromotionActivated",
              "detail": {
                "promotionId": "%s",
                "promotionName": "Test Promo",
                "skuIds": ["SKU-A"],
                "discountPct": 5.0,
                "validFrom": "2026-06-01",
                "validTo": "2026-06-30"
              }
            }
            """.formatted(promotionId);

        doThrow(new RuntimeException("port error")).when(activationPort).activate(any());

        assertThatThrownBy(() -> listener.onPromotionActivated(raw))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    void onPromotionActivated_validDates_parsedCorrectly() throws Exception {
        UUID promotionId = UUID.randomUUID();
        String raw = """
            {
              "source": "external.campaign-management",
              "detail-type": "PromotionActivated",
              "detail": {
                "promotionId": "%s",
                "promotionName": "Date Test Promo",
                "skuIds": ["SKU-X"],
                "discountPct": 20.0,
                "validFrom": "2026-08-01",
                "validTo": "2026-08-31"
              }
            }
            """.formatted(promotionId);

        listener.onPromotionActivated(raw);

        ArgumentCaptor<PromotionActivationCommand> captor =
                ArgumentCaptor.forClass(PromotionActivationCommand.class);
        verify(activationPort).activate(captor.capture());

        PromotionActivationCommand cmd = captor.getValue();
        assertThat(cmd.validFrom().getYear()).isEqualTo(2026);
        assertThat(cmd.validFrom().getMonthValue()).isEqualTo(8);
        assertThat(cmd.validTo().getDayOfMonth()).isEqualTo(31);
    }
}
