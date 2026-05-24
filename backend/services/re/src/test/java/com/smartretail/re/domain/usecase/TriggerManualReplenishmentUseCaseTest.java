package com.smartretail.re.domain.usecase;

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
class TriggerManualReplenishmentUseCaseTest {

    @Mock private ReplenishmentRepositoryPort repo;
    @Mock private PurchaseOrderEventPublisherPort publisher;

    private TriggerManualReplenishmentUseCase useCase;

    @BeforeEach
    void setUp() {
        useCase = new TriggerManualReplenishmentUseCase(repo, publisher);
    }

    private ReplenishmentRule rule(int moq, String costPerUnit) {
        ReplenishmentRule r = new ReplenishmentRule();
        r.setRuleId(UUID.randomUUID());
        r.setSupplierId("supplier-001");
        r.setSkuId("SKU-BEV-001");
        r.setDcId("DC-LONDON");
        r.setMoq(moq);
        r.setCostPerUnit(new BigDecimal(costPerUnit));
        r.setAutoApproveThreshold(new BigDecimal("50000.00"));
        return r;
    }

    @Test
    void trigger_createsPendingApprovalOrder() {
        when(repo.findActiveRule("SKU-BEV-001", "DC-LONDON"))
                .thenReturn(Optional.of(rule(50, "10.00")));

        PurchaseOrder result = useCase.trigger("SKU-BEV-001", "DC-LONDON", 100, null);

        assertThat(result.getWorkflowStatus()).isEqualTo(WorkflowStatus.PENDING_APPROVAL);
        assertThat(result.getSkuId()).isEqualTo("SKU-BEV-001");
        assertThat(result.getQuantity()).isEqualTo(100);
        verify(repo).savePurchaseOrder(any());
        verify(repo).saveLineItem(any());
        verify(publisher).publishPurchaseOrderEvent(any());
    }

    @Test
    void trigger_enforcesMoq_whenRequestedQuantityBelowMoq() {
        when(repo.findActiveRule("SKU-BEV-001", "DC-LONDON"))
                .thenReturn(Optional.of(rule(200, "10.00")));

        PurchaseOrder result = useCase.trigger("SKU-BEV-001", "DC-LONDON", 50, null);

        // MOQ is 200, requested 50 → effective quantity must be 200
        assertThat(result.getQuantity()).isEqualTo(200);
        assertThat(result.getTotalValue()).isEqualByComparingTo(new BigDecimal("2000.00"));
    }

    @Test
    void trigger_usesRequestedQuantity_whenAboveMoq() {
        when(repo.findActiveRule("SKU-BEV-001", "DC-LONDON"))
                .thenReturn(Optional.of(rule(50, "5.00")));

        PurchaseOrder result = useCase.trigger("SKU-BEV-001", "DC-LONDON", 300, "urgent");

        assertThat(result.getQuantity()).isEqualTo(300);
        assertThat(result.getTotalValue()).isEqualByComparingTo(new BigDecimal("1500.00"));
    }

    @Test
    void trigger_noActiveRule_throwsReplenishmentRuleNotFoundException() {
        when(repo.findActiveRule(any(), any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> useCase.trigger("SKU-MISSING", "DC-LONDON", 100, null))
                .isInstanceOf(ReplenishmentRuleNotFoundException.class);

        verify(repo, never()).savePurchaseOrder(any());
        verify(publisher, never()).publishPurchaseOrderEvent(any());
    }

    @Test
    void trigger_savedLineItemHasCorrectSkuAndQuantity() {
        when(repo.findActiveRule("SKU-BEV-001", "DC-LONDON"))
                .thenReturn(Optional.of(rule(50, "8.00")));

        useCase.trigger("SKU-BEV-001", "DC-LONDON", 100, null);

        var lineItemCaptor = ArgumentCaptor.forClass(com.smartretail.re.domain.model.PoLineItem.class);
        verify(repo).saveLineItem(lineItemCaptor.capture());
        var lineItem = lineItemCaptor.getValue();
        assertThat(lineItem.getQuantity()).isEqualTo(100);
        assertThat(lineItem.getLineTotal()).isEqualByComparingTo(new BigDecimal("800.00"));
    }
}
