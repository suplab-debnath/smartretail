package com.smartretail.ims.domain.model.exception;

public class InventoryPositionNotFoundException extends RuntimeException {

    public InventoryPositionNotFoundException(String skuId, String dcId) {
        super("No inventory position found for skuId=" + skuId + " dcId=" + dcId);
    }
}
