package com.smartretail.re.domain.model;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class PurchaseOrderTest {

    private ReplenishmentRule rule() {
        ReplenishmentRule r = new ReplenishmentRule();
        r.setRuleId(UUID.randomUUID());
        r.setSupplierId("supplier-001");
        r.setSkuId("SKU-BEV-001");
        r.setDcId("DC-LONDON");
        r.setMoq(50);
        r.setCostPerUnit(new BigDecimal("10.00"));
        r.setAutoApproveThreshold(new BigDecimal("50000.00"));
        return r;
    }

    @Test
    void create_setsAllFieldsFromRule() {
        ReplenishmentRule r = rule();
        UUID alertId = UUID.randomUUID();

        PurchaseOrder po = PurchaseOrder.create(r, 100, new BigDecimal("1000.00"),
                WorkflowStatus.PENDING_APPROVAL, alertId);

        assertThat(po.getPoId()).isNotNull();
        assertThat(po.getRuleId()).isEqualTo(r.getRuleId());
        assertThat(po.getSupplierId()).isEqualTo("supplier-001");
        assertThat(po.getSkuId()).isEqualTo("SKU-BEV-001");
        assertThat(po.getDcId()).isEqualTo("DC-LONDON");
        assertThat(po.getQuantity()).isEqualTo(100);
        assertThat(po.getTotalValue()).isEqualByComparingTo(new BigDecimal("1000.00"));
        assertThat(po.getWorkflowStatus()).isEqualTo(WorkflowStatus.PENDING_APPROVAL);
        assertThat(po.getVersion()).isEqualTo(0);
        assertThat(po.getAlertId()).isEqualTo(alertId);
        assertThat(po.getCreatedAt()).isNotNull();
        assertThat(po.getUpdatedAt()).isEqualTo(po.getCreatedAt());
    }

    @Test
    void setters_allowRehydrationFromDatabase() {
        PurchaseOrder po = new PurchaseOrder();
        UUID poId       = UUID.randomUUID();
        UUID ruleId     = UUID.randomUUID();
        UUID alertId    = UUID.randomUUID();
        Instant now     = Instant.now();

        po.setPoId(poId);
        po.setRuleId(ruleId);
        po.setSupplierId("supplier-002");
        po.setSkuId("SKU-FOOD-001");
        po.setDcId("DC-PARIS");
        po.setQuantity(200);
        po.setTotalValue(new BigDecimal("4000.00"));
        po.setWorkflowStatus(WorkflowStatus.APPROVED);
        po.setVersion(3);
        po.setApprovedBy("manager@test.com");
        po.setApprovedAt(now);
        po.setRejectedBy(null);
        po.setRejectedAt(null);
        po.setRejectionReason(null);
        po.setAlertId(alertId);
        po.setCreatedAt(now);
        po.setUpdatedAt(now);

        assertThat(po.getPoId()).isEqualTo(poId);
        assertThat(po.getRuleId()).isEqualTo(ruleId);
        assertThat(po.getSupplierId()).isEqualTo("supplier-002");
        assertThat(po.getSkuId()).isEqualTo("SKU-FOOD-001");
        assertThat(po.getDcId()).isEqualTo("DC-PARIS");
        assertThat(po.getQuantity()).isEqualTo(200);
        assertThat(po.getTotalValue()).isEqualByComparingTo(new BigDecimal("4000.00"));
        assertThat(po.getWorkflowStatus()).isEqualTo(WorkflowStatus.APPROVED);
        assertThat(po.getVersion()).isEqualTo(3);
        assertThat(po.getApprovedBy()).isEqualTo("manager@test.com");
        assertThat(po.getApprovedAt()).isEqualTo(now);
        assertThat(po.getRejectedBy()).isNull();
        assertThat(po.getRejectionReason()).isNull();
        assertThat(po.getAlertId()).isEqualTo(alertId);
    }
}
