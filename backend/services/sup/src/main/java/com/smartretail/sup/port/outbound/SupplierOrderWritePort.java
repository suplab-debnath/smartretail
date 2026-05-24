package com.smartretail.sup.port.outbound;

import java.util.UUID;

public interface SupplierOrderWritePort {

    /**
     * Inserts a new supplier_pos row with shipment status PENDING.
     * Returns the generated supplier_po_id.
     * Throws DuplicatePoException if a row already exists for the given poId.
     */
    UUID insertSupplierOrder(UUID poId, UUID supplierId, String skuId, String dcId, int quantity);

    class DuplicatePoException extends RuntimeException {
        public DuplicatePoException(UUID poId) {
            super("Supplier order already exists for PO: " + poId);
        }
    }
}
