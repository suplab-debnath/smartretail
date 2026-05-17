package com.smartretail.re.domain.usecase;

import com.smartretail.re.domain.model.PurchaseOrder;
import com.smartretail.re.domain.model.ReplenishmentRule;
import com.smartretail.re.domain.model.WorkflowStatus;
import com.smartretail.re.domain.model.exception.InvalidStatusTransitionException;
import com.smartretail.re.domain.model.exception.OptimisticLockException;
import com.smartretail.re.domain.model.exception.PurchaseOrderNotFoundException;
import com.smartretail.re.port.outbound.PurchaseOrderEventPublisherPort;
import com.smartretail.re.port.outbound.ReplenishmentRepositoryPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RejectPurchaseOrderUseCaseTest {

    @Mock private ReplenishmentRepositoryPort repo;
    @Mock private PurchaseOrderEventPublisherPort publisher;

    private RejectPurchaseOrderUseCase useCase;

    @BeforeEach
    void setUp() {
        useCase = new RejectPurchaseOrderUseCase(repo, publisher);
    }

    private PurchaseOrder poWithStatus(WorkflowStatus status) {
        ReplenishmentRule rule = new ReplenishmentRule();
        rule.setRuleId(UUID.randomUUID());
        rule.setSupplierId("supplier-001");
        rule.setSkuId("SKU-BEV-001");
        rule.setDcId("DC-LONDON");
        rule.setMoq(100);
        rule.setCostPerUnit(new BigDecimal("8.50"));
        return PurchaseOrder.create(rule, 100, new BigDecimal("850.00"), status, null);
    }

    @Test
    void reject_success_whenPendingApproval() {
        UUID poId = UUID.randomUUID();
        PurchaseOrder pending  = poWithStatus(WorkflowStatus.PENDING_APPROVAL);
        PurchaseOrder rejected = poWithStatus(WorkflowStatus.REJECTED);

        when(repo.findById(poId))
                .thenReturn(Optional.of(pending))
                .thenReturn(Optional.of(rejected));
        when(repo.updateStatus(eq(poId), eq(WorkflowStatus.REJECTED), eq(0),
                isNull(), isNull(), eq("planner@test.com"), any(), eq("surplus stock")))
                .thenReturn(1);

        PurchaseOrder result = useCase.reject(poId, 0, "planner@test.com", "surplus stock");

        assertThat(result.getWorkflowStatus()).isEqualTo(WorkflowStatus.REJECTED);
        verify(publisher).publishPurchaseOrderEvent(rejected);
    }

    @Test
    void reject_invalidStatus_throwsInvalidStatusTransition() {
        UUID poId = UUID.randomUUID();
        when(repo.findById(poId)).thenReturn(Optional.of(poWithStatus(WorkflowStatus.APPROVED)));

        assertThatThrownBy(() -> useCase.reject(poId, 0, "planner@test.com", "reason"))
                .isInstanceOf(InvalidStatusTransitionException.class);

        verify(repo, never()).updateStatus(any(), any(), anyInt(), any(), any(), any(), any(), any());
        verify(publisher, never()).publishPurchaseOrderEvent(any());
    }

    @Test
    void reject_optimisticLockFail_throwsOptimisticLockException() {
        UUID poId = UUID.randomUUID();
        when(repo.findById(poId)).thenReturn(Optional.of(poWithStatus(WorkflowStatus.PENDING_APPROVAL)));
        when(repo.updateStatus(any(), any(), anyInt(), any(), any(), any(), any(), any()))
                .thenReturn(0);

        assertThatThrownBy(() -> useCase.reject(poId, 0, "planner@test.com", "reason"))
                .isInstanceOf(OptimisticLockException.class)
                .hasMessageContaining("Concurrent modification");

        verify(publisher, never()).publishPurchaseOrderEvent(any());
    }

    @Test
    void reject_poNotFound_throwsPurchaseOrderNotFoundException() {
        UUID poId = UUID.randomUUID();
        when(repo.findById(poId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> useCase.reject(poId, 0, "planner@test.com", "reason"))
                .isInstanceOf(PurchaseOrderNotFoundException.class);
    }
}
