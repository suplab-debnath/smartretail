package com.smartretail.re.domain.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class WorkflowStatusTest {

    @Test
    void canApprove_trueOnlyForPendingApproval() {
        assertThat(WorkflowStatus.PENDING_APPROVAL.canApprove()).isTrue();
        for (WorkflowStatus s : WorkflowStatus.values()) {
            if (s != WorkflowStatus.PENDING_APPROVAL) {
                assertThat(s.canApprove()).as("canApprove() for %s", s).isFalse();
            }
        }
    }

    @Test
    void canReject_trueOnlyForPendingApproval() {
        assertThat(WorkflowStatus.PENDING_APPROVAL.canReject()).isTrue();
        for (WorkflowStatus s : WorkflowStatus.values()) {
            if (s != WorkflowStatus.PENDING_APPROVAL) {
                assertThat(s.canReject()).as("canReject() for %s", s).isFalse();
            }
        }
    }
}
