package com.smartretail.re.adapter.outbound.persistence;

import com.smartretail.re.domain.model.PurchaseOrder;
import com.smartretail.re.domain.model.WorkflowStatus;
import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "purchase_orders", schema = "replenishment")
public class PurchaseOrderJpaEntity {

    @Id
    @Column(name = "po_id", nullable = false)
    private UUID poId;

    @Column(name = "rule_id", nullable = false)
    private UUID ruleId;

    @Column(name = "supplier_id", nullable = false, length = 50)
    private String supplierId;

    @Column(name = "sku_id", nullable = false, length = 50)
    private String skuId;

    @Column(name = "dc_id", nullable = false, length = 50)
    private String dcId;

    @Column(name = "quantity", nullable = false)
    private int quantity;

    @Column(name = "total_value", nullable = false, precision = 12, scale = 2)
    private BigDecimal totalValue;

    @Column(name = "workflow_status", nullable = false, length = 30)
    private String workflowStatus;

    // Plain column — version managed manually via native updateStatus query
    @Column(name = "version", nullable = false)
    private int version;

    @Column(name = "approved_by", length = 100)
    private String approvedBy;

    @Column(name = "approved_at")
    private Instant approvedAt;

    @Column(name = "rejected_by", length = 100)
    private String rejectedBy;

    @Column(name = "rejected_at")
    private Instant rejectedAt;

    @Column(name = "rejection_reason")
    private String rejectionReason;

    @Column(name = "alert_id")
    private UUID alertId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected PurchaseOrderJpaEntity() {
    }

    public static PurchaseOrderJpaEntity from(PurchaseOrder po) {
        PurchaseOrderJpaEntity e = new PurchaseOrderJpaEntity();
        e.poId = po.getPoId();
        e.ruleId = po.getRuleId();
        e.supplierId = po.getSupplierId();
        e.skuId = po.getSkuId();
        e.dcId = po.getDcId();
        e.quantity = po.getQuantity();
        e.totalValue = po.getTotalValue();
        e.workflowStatus = po.getWorkflowStatus().name();
        e.version = po.getVersion();
        e.approvedBy = po.getApprovedBy();
        e.approvedAt = po.getApprovedAt();
        e.rejectedBy = po.getRejectedBy();
        e.rejectedAt = po.getRejectedAt();
        e.rejectionReason = po.getRejectionReason();
        e.alertId = po.getAlertId();
        e.createdAt = po.getCreatedAt();
        e.updatedAt = po.getUpdatedAt();
        return e;
    }

    public PurchaseOrder toDomain() {
        PurchaseOrder po = new PurchaseOrder();
        po.setPoId(poId);
        po.setRuleId(ruleId);
        po.setSupplierId(supplierId);
        po.setSkuId(skuId);
        po.setDcId(dcId);
        po.setQuantity(quantity);
        po.setTotalValue(totalValue);
        po.setWorkflowStatus(WorkflowStatus.valueOf(workflowStatus));
        po.setVersion(version);
        po.setApprovedBy(approvedBy);
        po.setApprovedAt(approvedAt);
        po.setRejectedBy(rejectedBy);
        po.setRejectedAt(rejectedAt);
        po.setRejectionReason(rejectionReason);
        po.setAlertId(alertId);
        po.setCreatedAt(createdAt);
        po.setUpdatedAt(updatedAt);
        return po;
    }

    public UUID getPoId() {
        return poId;
    }
}
