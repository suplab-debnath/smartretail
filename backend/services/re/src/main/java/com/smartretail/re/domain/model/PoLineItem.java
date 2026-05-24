package com.smartretail.re.domain.model;

import java.math.BigDecimal;
import java.util.UUID;

public class PoLineItem {

    private UUID lineId;
    private UUID poId;
    private String skuId;
    private int quantity;
    private BigDecimal unitCost;
    private BigDecimal lineTotal;

    public PoLineItem() {}

    public PoLineItem(UUID lineId, UUID poId, String skuId,
                      int quantity, BigDecimal unitCost, BigDecimal lineTotal) {
        this.lineId = lineId;
        this.poId = poId;
        this.skuId = skuId;
        this.quantity = quantity;
        this.unitCost = unitCost;
        this.lineTotal = lineTotal;
    }

    public UUID getLineId()         { return lineId; }
    public UUID getPoId()           { return poId; }
    public String getSkuId()        { return skuId; }
    public int getQuantity()        { return quantity; }
    public BigDecimal getUnitCost() { return unitCost; }
    public BigDecimal getLineTotal(){ return lineTotal; }

    public void setLineId(UUID lineId)              { this.lineId = lineId; }
    public void setPoId(UUID poId)                  { this.poId = poId; }
    public void setSkuId(String skuId)              { this.skuId = skuId; }
    public void setQuantity(int quantity)            { this.quantity = quantity; }
    public void setUnitCost(BigDecimal unitCost)    { this.unitCost = unitCost; }
    public void setLineTotal(BigDecimal lineTotal)  { this.lineTotal = lineTotal; }
}
