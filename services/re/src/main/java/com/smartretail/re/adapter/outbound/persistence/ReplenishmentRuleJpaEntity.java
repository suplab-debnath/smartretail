package com.smartretail.re.adapter.outbound.persistence;

import com.smartretail.re.domain.model.ReplenishmentRule;
import jakarta.persistence.*;

import java.math.BigDecimal;
import java.util.UUID;

@Entity
@Table(name = "replenishment_rules", schema = "replenishment")
public class ReplenishmentRuleJpaEntity {

    @Id
    @Column(name = "rule_id", nullable = false)
    private UUID ruleId;

    @Column(name = "supplier_id", nullable = false, length = 50)
    private String supplierId;

    @Column(name = "sku_id", nullable = false, length = 50)
    private String skuId;

    @Column(name = "dc_id", nullable = false, length = 50)
    private String dcId;

    @Column(name = "lead_time_days", nullable = false)
    private int leadTimeDays;

    @Column(name = "moq", nullable = false)
    private int moq;

    @Column(name = "cost_per_unit", nullable = false, precision = 10, scale = 2)
    private BigDecimal costPerUnit;

    @Column(name = "auto_approve_threshold", nullable = false, precision = 12, scale = 2)
    private BigDecimal autoApproveThreshold;

    @Column(name = "active", nullable = false)
    private boolean active;

    protected ReplenishmentRuleJpaEntity() {
    }

    public ReplenishmentRule toDomain() {
        ReplenishmentRule rule = new ReplenishmentRule();
        rule.setRuleId(ruleId);
        rule.setSupplierId(supplierId);
        rule.setSkuId(skuId);
        rule.setDcId(dcId);
        rule.setLeadTimeDays(leadTimeDays);
        rule.setMoq(moq);
        rule.setCostPerUnit(costPerUnit);
        rule.setAutoApproveThreshold(autoApproveThreshold);
        rule.setActive(active);
        return rule;
    }

    public UUID getRuleId() {
        return ruleId;
    }
}
