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
class ApprovePurchaseOrderUseCaseTest {

    @Mock
    private ReplenishmentRepositoryPort repo;

    @Mock
    private PurchaseOrderEventPublisherPort publisher;

    private ApprovePurchaseOrderUseCase useCase;

    @BeforeEach
    void setUp() {
        useCase = new ApprovePurchaseOrderUseCase(repo, publisher);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private PurchaseOrder poWithStatus(WorkflowStatus status) {
        var rule = new ReplenishmentRule();
        rule.setRuleId(UUID.randomUUID());
        rule.setSupplierId("supplier-001");
        rule.setSkuId("SKU-BEV-001");
        rule.setDcId("DC-LONDON");
        rule.setMoq(100);
        rule.setCostPerUnit(new BigDecimal("8.50"));
        rule.setAutoApproveThreshold(new BigDecimal("50000.00"));

        PurchaseOrder po = PurchaseOrder.create(rule, 100, new BigDecimal("850.00"), status,
                UUID.randomUUID());
        return po;
    }

    // -------------------------------------------------------------------------
    // Happy path
    // -------------------------------------------------------------------------

    @Test
    void approve_success_whenPendingApproval() {
        UUID poId = UUID.randomUUID();
        PurchaseOrder pending = poWithStatus(WorkflowStatus.PENDING_APPROVAL);
        PurchaseOrder approved = poWithStatus(WorkflowStatus.APPROVED);

        when(repo.findById(poId))
                .thenReturn(Optional.of(pending))   // first call: pre-check
                .thenReturn(Optional.of(approved));  // second call: fresh read after update
        when(repo.updateStatus(eq(poId), eq(WorkflowStatus.APPROVED), eq(0),
                eq("planner@test.com"), any(), isNull(), isNull(), isNull()))
                .thenReturn(1);

        PurchaseOrder result = useCase.approve(poId, 0, "planner@test.com");

        assertThat(result.getWorkflowStatus()).isEqualTo(WorkflowStatus.APPROVED);
        verify(publisher).publishPurchaseOrderEvent(approved);
    }

    // -------------------------------------------------------------------------
    // Invalid status transition — DRAFT → approve → 409
    // -------------------------------------------------------------------------

    @Test
    void approve_onDraft_throwsInvalidStatusTransition() {
        UUID poId = UUID.randomUUID();
        PurchaseOrder draft = poWithStatus(WorkflowStatus.DRAFT);
        when(repo.findById(poId)).thenReturn(Optional.of(draft));

        assertThatThrownBy(() -> useCase.approve(poId, 0, "planner@test.com"))
                .isInstanceOf(InvalidStatusTransitionException.class)
                .hasMessageContaining("PENDING_APPROVAL");

        verify(repo, never()).updateStatus(any(), any(), anyInt(), any(), any(), any(), any(), any());
        verify(publisher, never()).publishPurchaseOrderEvent(any());
    }

    // -------------------------------------------------------------------------
    // Invalid status transition — already APPROVED → approve → 409
    // -------------------------------------------------------------------------

    @Test
    void approve_onAlreadyApproved_throwsInvalidStatusTransition() {
        UUID poId = UUID.randomUUID();
        PurchaseOrder alreadyApproved = poWithStatus(WorkflowStatus.APPROVED);
        when(repo.findById(poId)).thenReturn(Optional.of(alreadyApproved));

        assertThatThrownBy(() -> useCase.approve(poId, 0, "planner@test.com"))
                .isInstanceOf(InvalidStatusTransitionException.class)
                .hasMessageContaining("PENDING_APPROVAL");

        verify(repo, never()).updateStatus(any(), any(), anyInt(), any(), any(), any(), any(), any());
    }

    // -------------------------------------------------------------------------
    // Optimistic lock failure → 409
    // -------------------------------------------------------------------------

    @Test
    void approve_optimisticLockFail_throwsOptimisticLockException() {
        UUID poId = UUID.randomUUID();
        PurchaseOrder pending = poWithStatus(WorkflowStatus.PENDING_APPROVAL);
        when(repo.findById(poId)).thenReturn(Optional.of(pending));
        when(repo.updateStatus(any(), any(), anyInt(), any(), any(), any(), any(), any()))
                .thenReturn(0); // 0 rows updated = version mismatch

        assertThatThrownBy(() -> useCase.approve(poId, 0, "planner@test.com"))
                .isInstanceOf(OptimisticLockException.class)
                .hasMessageContaining("Concurrent modification");

        verify(publisher, never()).publishPurchaseOrderEvent(any());
    }

    // -------------------------------------------------------------------------
    // PO not found → 404
    // -------------------------------------------------------------------------

    @Test
    void approve_poNotFound_throwsPurchaseOrderNotFoundException() {
        UUID poId = UUID.randomUUID();
        when(repo.findById(poId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> useCase.approve(poId, 0, "planner@test.com"))
                .isInstanceOf(PurchaseOrderNotFoundException.class);
    }
}
