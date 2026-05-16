package com.smartretail.re.domain.usecase;

import com.smartretail.re.domain.model.InventoryAlertEventDto;
import com.smartretail.re.domain.model.PoLineItem;
import com.smartretail.re.domain.model.PurchaseOrder;
import com.smartretail.re.domain.model.ReplenishmentRule;
import com.smartretail.re.domain.model.WorkflowStatus;
import com.smartretail.re.domain.model.exception.ReplenishmentRuleNotFoundException;
import com.smartretail.re.port.outbound.PurchaseOrderEventPublisherPort;
import com.smartretail.re.port.outbound.ReplenishmentRepositoryPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ProcessInventoryAlertUseCaseTest {

    @Mock
    private ReplenishmentRepositoryPort repo;

    @Mock
    private PurchaseOrderEventPublisherPort publisher;

    private ProcessInventoryAlertUseCase useCase;

    @BeforeEach
    void setUp() {
        useCase = new ProcessInventoryAlertUseCase(repo, publisher);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private ReplenishmentRule rule(BigDecimal autoApproveThreshold, int moq, BigDecimal cost) {
        var r = new ReplenishmentRule();
        r.setRuleId(UUID.randomUUID());
        r.setSupplierId("supplier-001");
        r.setSkuId("SKU-BEV-001");
        r.setDcId("DC-LONDON");
        r.setLeadTimeDays(5);
        r.setMoq(moq);
        r.setCostPerUnit(cost);
        r.setAutoApproveThreshold(autoApproveThreshold);
        r.setActive(true);
        return r;
    }

    private InventoryAlertEventDto alert(String skuId, String dcId, int thresholdValue, int actualValue) {
        return new InventoryAlertEventDto(
                UUID.randomUUID().toString(),
                UUID.randomUUID().toString(),
                skuId,
                dcId,
                "LOW_STOCK",
                "HIGH",
                thresholdValue,
                actualValue,
                "ACTIVE"
        );
    }

    // -------------------------------------------------------------------------
    // Scenario 2a — Auto-Approve
    // -------------------------------------------------------------------------

    @Test
    void scenario2a_autoApprove_whenTotalValueBelowThreshold() {
        // Rule 1: moq=100, cost=8.50, threshold=50000
        // Alert: thresholdValue=100 (reorderPoint), actualValue=90 (onHand)
        // quantity = max(100-90, 100) = max(10, 100) = 100
        // totalValue = 100 × 8.50 = 850.00 ≤ 50000 → APPROVED
        ReplenishmentRule r = rule(new BigDecimal("50000.00"), 100, new BigDecimal("8.50"));
        InventoryAlertEventDto ev = alert("SKU-BEV-001", "DC-LONDON", 100, 90);

        when(repo.findActiveRule("SKU-BEV-001", "DC-LONDON")).thenReturn(Optional.of(r));

        useCase.processInventoryAlert(ev);

        ArgumentCaptor<PurchaseOrder> poCaptor = ArgumentCaptor.forClass(PurchaseOrder.class);
        verify(repo).savePurchaseOrder(poCaptor.capture());

        PurchaseOrder saved = poCaptor.getValue();
        assertThat(saved.getWorkflowStatus()).isEqualTo(WorkflowStatus.APPROVED);
        assertThat(saved.getQuantity()).isEqualTo(100);
        assertThat(saved.getTotalValue()).isEqualByComparingTo(new BigDecimal("850.00"));
        assertThat(saved.getVersion()).isEqualTo(0);
        assertThat(saved.getAlertId()).isEqualTo(UUID.fromString(ev.alertId()));

        verify(repo).saveLineItem(any(PoLineItem.class));
        verify(publisher).publishPurchaseOrderEvent(saved);
    }

    // -------------------------------------------------------------------------
    // Scenario 2b — Pending Approval
    // -------------------------------------------------------------------------

    @Test
    void scenario2b_pendingApproval_whenTotalValueExceedsThreshold() {
        // Rule 2: moq=50, cost=75.00, threshold=0.00 → always PENDING_APPROVAL
        ReplenishmentRule r = rule(new BigDecimal("0.00"), 50, new BigDecimal("75.00"));
        InventoryAlertEventDto ev = alert("SKU-BEV-003", "DC-LONDON", 100, 20);

        when(repo.findActiveRule("SKU-BEV-003", "DC-LONDON")).thenReturn(Optional.of(r));

        useCase.processInventoryAlert(ev);

        ArgumentCaptor<PurchaseOrder> poCaptor = ArgumentCaptor.forClass(PurchaseOrder.class);
        verify(repo).savePurchaseOrder(poCaptor.capture());

        assertThat(poCaptor.getValue().getWorkflowStatus()).isEqualTo(WorkflowStatus.PENDING_APPROVAL);
        verify(publisher).publishPurchaseOrderEvent(poCaptor.getValue());
    }

    // -------------------------------------------------------------------------
    // MOQ floor applied when gap < moq
    // -------------------------------------------------------------------------

    @Test
    void moqFloor_appliedWhenGapBelowMoq() {
        // gap = 100 - 95 = 5, moq = 100 → quantity should be 100 (moq wins)
        ReplenishmentRule r = rule(new BigDecimal("50000.00"), 100, new BigDecimal("8.50"));
        InventoryAlertEventDto ev = alert("SKU-BEV-001", "DC-LONDON", 100, 95);

        when(repo.findActiveRule("SKU-BEV-001", "DC-LONDON")).thenReturn(Optional.of(r));

        useCase.processInventoryAlert(ev);

        ArgumentCaptor<PurchaseOrder> poCaptor = ArgumentCaptor.forClass(PurchaseOrder.class);
        verify(repo).savePurchaseOrder(poCaptor.capture());

        assertThat(poCaptor.getValue().getQuantity()).isEqualTo(100);
    }

    // -------------------------------------------------------------------------
    // No rule found
    // -------------------------------------------------------------------------

    @Test
    void noRuleFound_throwsReplenishmentRuleNotFoundException() {
        InventoryAlertEventDto ev = alert("SKU-UNKNOWN", "DC-LONDON", 50, 10);
        when(repo.findActiveRule("SKU-UNKNOWN", "DC-LONDON")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> useCase.processInventoryAlert(ev))
                .isInstanceOf(ReplenishmentRuleNotFoundException.class)
                .hasMessageContaining("SKU-UNKNOWN");

        verify(repo, never()).savePurchaseOrder(any());
        verify(publisher, never()).publishPurchaseOrderEvent(any());
    }
}
