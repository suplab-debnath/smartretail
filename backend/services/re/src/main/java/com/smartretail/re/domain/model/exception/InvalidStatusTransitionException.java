package com.smartretail.re.domain.model.exception;

import com.smartretail.re.domain.model.WorkflowStatus;

public class InvalidStatusTransitionException extends RuntimeException {

    private final String currentStatus;

    public InvalidStatusTransitionException(WorkflowStatus current, String action) {
        super("Purchase order cannot be " + action + " from status " + current.name()
                + ". Status must be PENDING_APPROVAL.");
        this.currentStatus = current.name();
    }

    public String getCurrentStatus() {
        return currentStatus;
    }
}
