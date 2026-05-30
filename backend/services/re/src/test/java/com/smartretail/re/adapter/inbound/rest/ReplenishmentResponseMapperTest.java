package com.smartretail.re.adapter.inbound.rest;

import com.smartretail.re.adapter.inbound.rest.generated.model.PoLineItem;
import com.smartretail.re.adapter.inbound.rest.generated.model.PurchaseOrder;
import com.smartretail.re.adapter.inbound.rest.generated.model.WorkflowStatus;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ReplenishmentResponseMapperTest {

    private final ReplenishmentResponseMapper mapper = Mappers.getMapper(ReplenishmentResponseMapper.class);

    @Test
    void toApiModel_withNullPurchaseOrder_returnsNull() {
        assertThat(mapper.toApiModel((com.smartretail.re.domain.model.PurchaseOrder) null)).isNull();
    }

    @Test
    void toApiModel_withValidPurchaseOrder_mapsFieldsCorrectly() {
        var domainPo = new com.smartretail.re.domain.model.PurchaseOrder();
        UUID poId = UUID.randomUUID();
        UUID ruleId = UUID.randomUUID();
        UUID alertId = UUID.randomUUID();
        Instant now = Instant.parse("2026-05-30T12:00:00Z");

        domainPo.setPoId(poId);
        domainPo.setRuleId(ruleId);
        domainPo.setSupplierId("supplier-123");
        domainPo.setSkuId("SKU-BEV-001");
        domainPo.setDcId("DC-LONDON");
        domainPo.setQuantity(150);
        domainPo.setTotalValue(new BigDecimal("1275.50"));
        domainPo.setWorkflowStatus(com.smartretail.re.domain.model.WorkflowStatus.APPROVED);
        domainPo.setVersion(2);
        domainPo.setApprovedBy("planner-1");
        domainPo.setApprovedAt(now.plusSeconds(60));
        domainPo.setRejectedBy("planner-2");
        domainPo.setRejectedAt(now.plusSeconds(120));
        domainPo.setRejectionReason("rejection-reason-1");
        domainPo.setAlertId(alertId);
        domainPo.setCreatedAt(now);
        domainPo.setUpdatedAt(now.plusSeconds(30));

        PurchaseOrder apiPo = mapper.toApiModel(domainPo);

        assertThat(apiPo).isNotNull();
        assertThat(apiPo.getPoId()).isEqualTo(poId);
        assertThat(apiPo.getRuleId()).isEqualTo(ruleId);
        assertThat(apiPo.getSupplierId()).isEqualTo("supplier-123");
        assertThat(apiPo.getSkuId()).isEqualTo("SKU-BEV-001");
        assertThat(apiPo.getDcId()).isEqualTo("DC-LONDON");
        assertThat(apiPo.getQuantity()).isEqualTo(150);
        assertThat(apiPo.getTotalValue()).isEqualTo(1275.50);
        assertThat(apiPo.getWorkflowStatus()).isEqualTo(WorkflowStatus.APPROVED);
        assertThat(apiPo.getVersion()).isEqualTo(2);
        assertThat(apiPo.getApprovedBy()).isEqualTo("planner-1");
        assertThat(apiPo.getApprovedAt().toInstant()).isEqualTo(now.plusSeconds(60));
        assertThat(apiPo.getRejectedBy()).isEqualTo("planner-2");
        assertThat(apiPo.getRejectedAt().toInstant()).isEqualTo(now.plusSeconds(120));
        assertThat(apiPo.getRejectionReason()).isEqualTo("rejection-reason-1");
        assertThat(apiPo.getAlertId()).isEqualTo(alertId);
        assertThat(apiPo.getCreatedAt().toInstant()).isEqualTo(now);
        assertThat(apiPo.getUpdatedAt().toInstant()).isEqualTo(now.plusSeconds(30));
        assertThat(apiPo.getLineItems()).isEmpty();
    }

    @Test
    void toApiModel_withLineItems_mapsCorrectly() {
        var domainPo = new com.smartretail.re.domain.model.PurchaseOrder();
        UUID poId = UUID.randomUUID();
        domainPo.setPoId(poId);
        domainPo.setWorkflowStatus(com.smartretail.re.domain.model.WorkflowStatus.PENDING_APPROVAL);

        var domainItem1 = new com.smartretail.re.domain.model.PoLineItem(
                UUID.randomUUID(), poId, "SKU-BEV-001", 100, new BigDecimal("8.50"), new BigDecimal("850.00")
        );
        var domainItem2 = new com.smartretail.re.domain.model.PoLineItem(
                UUID.randomUUID(), poId, "SKU-BEV-002", 50, new BigDecimal("8.50"), new BigDecimal("425.00")
        );

        PurchaseOrder apiPo = mapper.toApiModel(domainPo, List.of(domainItem1, domainItem2));

        assertThat(apiPo).isNotNull();
        assertThat(apiPo.getLineItems()).hasSize(2);

        PoLineItem apiItem1 = apiPo.getLineItems().get(0);
        assertThat(apiItem1.getLineId()).isEqualTo(domainItem1.getLineId());
        assertThat(apiItem1.getSkuId()).isEqualTo("SKU-BEV-001");
        assertThat(apiItem1.getQuantity()).isEqualTo(100);
        assertThat(apiItem1.getUnitCost()).isEqualTo(8.50);
        assertThat(apiItem1.getLineTotal()).isEqualTo(850.00);

        PoLineItem apiItem2 = apiPo.getLineItems().get(1);
        assertThat(apiItem2.getQuantity()).isEqualTo(50);
        assertThat(apiItem2.getLineTotal()).isEqualTo(425.00);
    }

    @Test
    void toApiModel_withEmptyLineItems_doesNotSetLineItems() {
        var domainPo = new com.smartretail.re.domain.model.PurchaseOrder();
        domainPo.setWorkflowStatus(com.smartretail.re.domain.model.WorkflowStatus.PENDING_APPROVAL);

        PurchaseOrder apiPo = mapper.toApiModel(domainPo, List.of());
        assertThat(apiPo.getLineItems()).isEmpty();
    }
}
