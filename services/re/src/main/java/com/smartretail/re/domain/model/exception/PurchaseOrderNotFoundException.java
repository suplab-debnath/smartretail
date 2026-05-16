package com.smartretail.re.domain.model.exception;

import java.util.UUID;

public class PurchaseOrderNotFoundException extends RuntimeException {

    public PurchaseOrderNotFoundException(UUID poId) {
        super("Purchase order not found: " + poId);
    }
}
