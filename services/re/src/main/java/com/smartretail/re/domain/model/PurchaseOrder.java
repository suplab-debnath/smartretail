package com.smartretail.re.domain.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public class PurchaseOrder {

    private UUID poId;
    private UUID ruleId;
    private String supplierId;
    private String skuId;
    private String dcId;
    private int quantity;
    private BigDecimal totalValue;
    private WorkflowStatus workflowStatus;
    private int version;
    private String approvedBy;
    private Instant approvedAt;
    private String rejectedBy;
    private Instant rejectedAt;
    private String rejectionReason;
    private UUID alertId;
    private Instant createdAt;
    private Instant updatedAt;

    public PurchaseOrder() {}

    /**
     * Factory method for creating a new PurchaseOrder from a replenishment rule.
     * Sets version=0 and timestamps to now.
     */
    public static PurchaseOrder create(ReplenishmentRule rule,
                                       int quantity,
                                       BigDecimal totalValue,
                                       WorkflowStatus status,
                                       UUID alertId) {
        PurchaseOrder po = new PurchaseOrder();
        po.poId           = UUID.randomUUID();
        po.ruleId         = rule.getRuleId();
        po.supplierId     = rule.getSupplierId();
        po.skuId          = rule.getSkuId();
        po.dcId           = rule.getDcId();
        po.quantity       = quantity;
        po.totalValue     = totalValue;
        po.workflowStatus = status;
        po.version        = 0;
        po.alertId        = alertId;
        po.createdAt      = Instant.now();
        po.updatedAt      = po.createdAt;
        return po;
    }

    public UUID getPoId()               { return poId; }
    public UUID getRuleId()             { return ruleId; }
    public String getSupplierId()       { return supplierId; }
    public String getSkuId()            { return skuId; }
    public String getDcId()             { return dcId; }
    public int getQuantity()            { return quantity; }
    public BigDecimal getTotalValue()   { return totalValue; }
    public WorkflowStatus getWorkflowStatus() { return workflowStatus; }
    public int getVersion()             { return version; }
    public String getApprovedBy()       { return approvedBy; }
    public Instant getApprovedAt()      { return approvedAt; }
    public String getRejectedBy()       { return rejectedBy; }
    public Instant getRejectedAt()      { return rejectedAt; }
    public String getRejectionReason()  { return rejectionReason; }
    public UUID getAlertId()            { return alertId; }
    public Instant getCreatedAt()       { return createdAt; }
    public Instant getUpdatedAt()       { return updatedAt; }

    public void setPoId(UUID poId)                          { this.poId = poId; }
    public void setRuleId(UUID ruleId)                      { this.ruleId = ruleId; }
    public void setSupplierId(String supplierId)            { this.supplierId = supplierId; }
    public void setSkuId(String skuId)                      { this.skuId = skuId; }
    public void setDcId(String dcId)                        { this.dcId = dcId; }
    public void setQuantity(int quantity)                   { this.quantity = quantity; }
    public void setTotalValue(BigDecimal totalValue)        { this.totalValue = totalValue; }
    public void setWorkflowStatus(WorkflowStatus s)        { this.workflowStatus = s; }
    public void setVersion(int version)                    { this.version = version; }
    public void setApprovedBy(String approvedBy)           { this.approvedBy = approvedBy; }
    public void setApprovedAt(Instant approvedAt)          { this.approvedAt = approvedAt; }
    public void setRejectedBy(String rejectedBy)           { this.rejectedBy = rejectedBy; }
    public void setRejectedAt(Instant rejectedAt)          { this.rejectedAt = rejectedAt; }
    public void setRejectionReason(String rejectionReason) { this.rejectionReason = rejectionReason; }
    public void setAlertId(UUID alertId)                   { this.alertId = alertId; }
    public void setCreatedAt(Instant createdAt)            { this.createdAt = createdAt; }
    public void setUpdatedAt(Instant updatedAt)            { this.updatedAt = updatedAt; }
}
