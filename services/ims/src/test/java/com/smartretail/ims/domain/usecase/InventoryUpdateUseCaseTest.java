package com.smartretail.ims.domain.usecase;

import com.smartretail.ims.domain.model.AlertSeverity;
import com.smartretail.ims.domain.model.AlertType;
import com.smartretail.ims.domain.model.InventoryPosition;
import com.smartretail.ims.domain.model.SalesTransactionEventDto;
import com.smartretail.ims.domain.model.StockAlert;
import com.smartretail.ims.domain.model.exception.InventoryPositionNotFoundException;
import com.smartretail.ims.domain.model.exception.OptimisticLockException;
import com.smartretail.ims.port.outbound.AlertPublisherPort;
import com.smartretail.ims.port.outbound.InventoryRepositoryPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class InventoryUpdateUseCaseTest {

    @Mock private InventoryRepositoryPort inventoryRepo;
    @Mock private AlertPublisherPort alertPublisher;

    private InventoryUpdateUseCase useCase;

    @BeforeEach
    void setUp() {
        useCase = new InventoryUpdateUseCase(inventoryRepo, alertPublisher);
    }

    @Test
    void processSalesEvent_aboveReorderPoint_noAlert() {
        InventoryPosition pos = buildPosition(150, 100, 0);
        when(inventoryRepo.findBySkuAndDc("SKU-BEV-001", "DC-LONDON")).thenReturn(Optional.of(pos));
        when(inventoryRepo.decrementOnHand(pos.getPositionId(), 30, 0)).thenReturn(1);
        // After decrement: on_hand=120, atp=120 >= reorderPoint=100 → no alert
        InventoryPosition reloaded = buildPosition(120, 100, 0);
        when(inventoryRepo.findById(pos.getPositionId())).thenReturn(Optional.of(reloaded));

        useCase.processSalesEvent(event(30));

        verify(inventoryRepo).decrementOnHand(pos.getPositionId(), 30, 0);
        verifyNoInteractions(alertPublisher);
        verify(inventoryRepo, never()).saveAlert(any());
    }

    @Test
    void processSalesEvent_belowReorderPoint_raisesAlertAndPublishes() {
        InventoryPosition pos = buildPosition(120, 100, 0);
        when(inventoryRepo.findBySkuAndDc("SKU-BEV-001", "DC-LONDON")).thenReturn(Optional.of(pos));
        when(inventoryRepo.decrementOnHand(pos.getPositionId(), 30, 0)).thenReturn(1);
        // After decrement: on_hand=90, atp=90 < reorderPoint=100 → MEDIUM alert
        InventoryPosition reloaded = buildPosition(90, 100, 1);
        when(inventoryRepo.findById(pos.getPositionId())).thenReturn(Optional.of(reloaded));

        useCase.processSalesEvent(event(30));

        ArgumentCaptor<StockAlert> alertCaptor = ArgumentCaptor.forClass(StockAlert.class);
        verify(inventoryRepo).saveAlert(alertCaptor.capture());
        StockAlert saved = alertCaptor.getValue();
        assertThat(saved.getAlertType()).isEqualTo(AlertType.LOW_STOCK);
        assertThat(saved.getSeverity()).isEqualTo(AlertSeverity.MEDIUM);
        assertThat(saved.getActualValue()).isEqualTo(90);
        verify(alertPublisher).publishInventoryAlertEvent(eq(saved), eq(reloaded));
    }

    @Test
    void processSalesEvent_noPosition_throwsNotFoundException() {
        when(inventoryRepo.findBySkuAndDc(any(), any())).thenReturn(Optional.empty());
        assertThatThrownBy(() -> useCase.processSalesEvent(event(10)))
                .isInstanceOf(InventoryPositionNotFoundException.class);
    }

    @Test
    void processSalesEvent_optimisticLockFails_throwsAfterMaxRetries() {
        InventoryPosition pos = buildPosition(120, 100, 0);
        when(inventoryRepo.findBySkuAndDc(any(), any())).thenReturn(Optional.of(pos));
        // Simulate all retries returning 0 rows updated
        when(inventoryRepo.decrementOnHand(any(), anyInt(), anyInt())).thenReturn(0);
        when(inventoryRepo.findById(any())).thenReturn(Optional.of(pos));

        assertThatThrownBy(() -> useCase.processSalesEvent(event(10)))
                .isInstanceOf(OptimisticLockException.class);
        // decrementOnHand should be attempted MAX_RETRIES=3 times
        verify(inventoryRepo, times(3)).decrementOnHand(any(), anyInt(), anyInt());
    }

    private InventoryPosition buildPosition(int onHand, int reorderPoint, int version) {
        var pos = new InventoryPosition();
        pos.setPositionId(UUID.randomUUID());
        pos.setSkuId("SKU-BEV-001");
        pos.setDcId("DC-LONDON");
        pos.setOnHand(onHand);
        pos.setReserved(0);
        pos.setReorderPoint(reorderPoint);
        pos.setSafetyStock(30);
        pos.setVersion(version);
        return pos;
    }

    private SalesTransactionEventDto event(int quantity) {
        return new SalesTransactionEventDto(
                UUID.randomUUID(), "STORE-001", "SKU-BEV-001", "DC-LONDON",
                quantity, BigDecimal.valueOf(8.50), "POS",
                Instant.parse("2026-05-15T14:23:00Z")
        );
    }
}
