package com.smartretail.re.domain.model;

public enum WorkflowStatus {

    DRAFT,
    PENDING_APPROVAL,
    APPROVED,
    REJECTED,
    EXPIRED,
    DISPATCHED,
    ACKNOWLEDGED,
    SHIPPED,
    PARTIAL_DELIVERY,
    COMPLETED,
    CANCELLED;

    public boolean canApprove() {
        return this == PENDING_APPROVAL;
    }

    public boolean canReject() {
        return this == PENDING_APPROVAL;
    }
}
