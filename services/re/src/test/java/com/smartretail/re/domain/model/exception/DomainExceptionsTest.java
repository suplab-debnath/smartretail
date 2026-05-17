package com.smartretail.re.domain.model.exception;

import com.smartretail.re.domain.model.WorkflowStatus;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class DomainExceptionsTest {

    @Test
    void invalidStatusTransition_messageAndCurrentStatus() {
        InvalidStatusTransitionException ex =
                new InvalidStatusTransitionException(WorkflowStatus.DRAFT, "approved");
        assertThat(ex.getCurrentStatus()).isEqualTo("DRAFT");
        assertThat(ex.getMessage()).contains("PENDING_APPROVAL");
    }

    @Test
    void purchaseOrderNotFound_message() {
        UUID poId = UUID.randomUUID();
        PurchaseOrderNotFoundException ex = new PurchaseOrderNotFoundException(poId);
        assertThat(ex.getMessage()).contains(poId.toString());
        assertThat(ex).isInstanceOf(RuntimeException.class);
    }

    @Test
    void replenishmentRuleNotFound_message() {
        ReplenishmentRuleNotFoundException ex =
                new ReplenishmentRuleNotFoundException("SKU-001", "DC-LONDON");
        assertThat(ex.getMessage()).contains("SKU-001").contains("DC-LONDON");
    }

    @Test
    void optimisticLock_message() {
        OptimisticLockException ex = new OptimisticLockException("Concurrent modification detected");
        assertThat(ex.getMessage()).isEqualTo("Concurrent modification detected");
    }
}
