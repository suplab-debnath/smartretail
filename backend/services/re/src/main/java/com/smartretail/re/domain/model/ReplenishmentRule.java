package com.smartretail.re.domain.model;

import java.math.BigDecimal;
import java.util.UUID;

public class ReplenishmentRule {

    private UUID ruleId;
    private String supplierId;
    private String skuId;
    private String dcId;
    private int leadTimeDays;
    private int moq;
    private BigDecimal costPerUnit;
    private BigDecimal autoApproveThreshold;
    private boolean active;

    public ReplenishmentRule() {}

    public UUID getRuleId()                         { return ruleId; }
    public String getSupplierId()                   { return supplierId; }
    public String getSkuId()                        { return skuId; }
    public String getDcId()                         { return dcId; }
    public int getLeadTimeDays()                    { return leadTimeDays; }
    public int getMoq()                             { return moq; }
    public BigDecimal getCostPerUnit()              { return costPerUnit; }
    public BigDecimal getAutoApproveThreshold()     { return autoApproveThreshold; }
    public boolean isActive()                       { return active; }

    public void setRuleId(UUID ruleId)                              { this.ruleId = ruleId; }
    public void setSupplierId(String supplierId)                    { this.supplierId = supplierId; }
    public void setSkuId(String skuId)                              { this.skuId = skuId; }
    public void setDcId(String dcId)                                { this.dcId = dcId; }
    public void setLeadTimeDays(int leadTimeDays)                   { this.leadTimeDays = leadTimeDays; }
    public void setMoq(int moq)                                     { this.moq = moq; }
    public void setCostPerUnit(BigDecimal costPerUnit)              { this.costPerUnit = costPerUnit; }
    public void setAutoApproveThreshold(BigDecimal autoApproveThreshold) { this.autoApproveThreshold = autoApproveThreshold; }
    public void setActive(boolean active)                           { this.active = active; }
}
