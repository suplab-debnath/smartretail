package com.smartretail.re.port.inbound;

import com.smartretail.re.domain.model.PurchaseOrder;

public interface TriggerManualReplenishmentPort {

    /**
     * Creates a PENDING_APPROVAL purchase order triggered manually by an SC_PLANNER.
     * Looks up the active replenishment rule for the given SKU/DC to determine
     * supplier and unit cost.
     *
     * @param skuId    Stock Keeping Unit identifier
     * @param dcId     Distribution Centre identifier
     * @param quantity Number of units to order (must be >= 1)
     * @param notes    Optional planner notes (may be null)
     * @return the newly created PurchaseOrder (version=0, status=PENDING_APPROVAL)
     * @throws com.smartretail.re.domain.model.exception.ReplenishmentRuleNotFoundException
     *         if no active rule exists for the given skuId/dcId
     */
    PurchaseOrder trigger(String skuId, String dcId, int quantity, String notes);
}
