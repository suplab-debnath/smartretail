package com.smartretail.sis.domain.model.exception;

import java.util.UUID;

public class DuplicateEventException extends RuntimeException {

    private final UUID transactionId;

    public DuplicateEventException(UUID transactionId) {
        super("Event already processed: transactionId=" + transactionId);
        this.transactionId = transactionId;
    }

    public UUID getTransactionId() {
        return transactionId;
    }
}
