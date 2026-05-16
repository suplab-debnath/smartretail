package com.smartretail.re.domain.model.exception;

public class ReplenishmentRuleNotFoundException extends RuntimeException {

    public ReplenishmentRuleNotFoundException(String skuId, String dcId) {
        super("No active replenishment rule found for skuId=" + skuId + " dcId=" + dcId);
    }
}
