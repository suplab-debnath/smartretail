package com.smartretail.pps.domain.usecase;

import com.smartretail.pps.domain.model.PromotionActivationCommand;
import com.smartretail.pps.port.outbound.PromotionWritePort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PromotionActivationUseCaseTest {

    @Mock
    private PromotionWritePort writePort;

    private PromotionActivationUseCase useCase;

    @BeforeEach
    void setUp() {
        useCase = new PromotionActivationUseCase(writePort);
    }

    @Test
    void activate_validCommand_delegatesToWritePort() {
        PromotionActivationCommand command = makeCommand();

        useCase.activate(command);

        ArgumentCaptor<PromotionActivationCommand> captor =
                ArgumentCaptor.forClass(PromotionActivationCommand.class);
        verify(writePort).upsert(captor.capture());

        PromotionActivationCommand captured = captor.getValue();
        assertThat(captured.promotionId()).isEqualTo(command.promotionId());
        assertThat(captured.promotionName()).isEqualTo("Summer Beverages Promo");
        assertThat(captured.skuIds()).containsExactly("SKU-BEV-001", "SKU-BEV-002");
        assertThat(captured.discountPct()).isEqualByComparingTo(BigDecimal.valueOf(15.0));
    }

    @Test
    void activate_writePortThrows_propagatesException() {
        PromotionActivationCommand command = makeCommand();
        doThrow(new RuntimeException("db error")).when(writePort).upsert(any());

        assertThatThrownBy(() -> useCase.activate(command))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("db error");
    }

    @Test
    void activate_multipleSkus_passesAllSkuIds() {
        List<String> skus = List.of("SKU-A", "SKU-B", "SKU-C", "SKU-D");
        PromotionActivationCommand command = new PromotionActivationCommand(
                UUID.randomUUID(), "Multi-SKU Promo", skus,
                BigDecimal.valueOf(10.0),
                LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 30));

        useCase.activate(command);

        ArgumentCaptor<PromotionActivationCommand> captor =
                ArgumentCaptor.forClass(PromotionActivationCommand.class);
        verify(writePort).upsert(captor.capture());
        assertThat(captor.getValue().skuIds()).hasSize(4);
    }

    private PromotionActivationCommand makeCommand() {
        return new PromotionActivationCommand(
                UUID.randomUUID(),
                "Summer Beverages Promo",
                List.of("SKU-BEV-001", "SKU-BEV-002"),
                BigDecimal.valueOf(15.0),
                LocalDate.of(2026, 7, 1),
                LocalDate.of(2026, 7, 31));
    }
}
