package com.smartretail.re.adapter.outbound.persistence;

import com.smartretail.re.domain.model.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest(properties = "spring.jpa.hibernate.ddl-auto=none")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers(disabledWithoutDocker = true)
@Import(ReplenishmentRepository.class)
class ReplenishmentRepositoryTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15")
            .withInitScript("sql/schema-re.sql");

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    private ReplenishmentRepository repository;
    @Autowired
    private ReplenishmentRuleJpaRepository ruleRepo;

    private ReplenishmentRule rule;

    @BeforeEach
    void seedRule() {
        var entity = new ReplenishmentRuleJpaEntity();
        setField(entity, "ruleId", UUID.randomUUID());
        setField(entity, "supplierId", "SUP-001");
        setField(entity, "skuId", "SKU-BEV-001");
        setField(entity, "dcId", "DC-LONDON");
        setField(entity, "leadTimeDays", 7);
        setField(entity, "moq", 50);
        setField(entity, "costPerUnit", new BigDecimal("5.00"));
        setField(entity, "autoApproveThreshold", new BigDecimal("1000.00"));
        setField(entity, "active", true);
        ruleRepo.save(entity);
        rule = repository.findActiveRule("SKU-BEV-001", "DC-LONDON").orElseThrow();
    }

    private static void setField(Object obj, String name, Object value) {
        try {
            var field = obj.getClass().getDeclaredField(name);
            field.setAccessible(true);
            field.set(obj, value);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void findActiveRule_returnsRule() {
        Optional<ReplenishmentRule> result = repository.findActiveRule("SKU-BEV-001", "DC-LONDON");
        assertThat(result).isPresent().hasValueSatisfying(r -> {
            assertThat(r.getSupplierId()).isEqualTo("SUP-001");
            assertThat(r.getMoq()).isEqualTo(50);
            assertThat(r.isActive()).isTrue();
        });
    }

    @Test
    void findActiveRule_unknownSku_returnsEmpty() {
        assertThat(repository.findActiveRule("UNKNOWN", "DC-LONDON")).isEmpty();
    }

    @Test
    void savePurchaseOrder_persistsOrder() {
        PurchaseOrder po = PurchaseOrder.create(rule, 100, new BigDecimal("500.00"),
                WorkflowStatus.PENDING_APPROVAL, UUID.randomUUID());

        repository.savePurchaseOrder(po);

        Optional<PurchaseOrder> found = repository.findById(po.getPoId());
        assertThat(found).isPresent().hasValueSatisfying(p -> {
            assertThat(p.getSkuId()).isEqualTo("SKU-BEV-001");
            assertThat(p.getWorkflowStatus()).isEqualTo(WorkflowStatus.PENDING_APPROVAL);
            assertThat(p.getVersion()).isEqualTo(0);
            assertThat(p.getQuantity()).isEqualTo(100);
        });
    }

    @Test
    void updateStatus_withCorrectVersion_returns1AndTransitions() {
        PurchaseOrder po = PurchaseOrder.create(rule, 50, new BigDecimal("250.00"),
                WorkflowStatus.PENDING_APPROVAL, null);
        repository.savePurchaseOrder(po);

        int rows = repository.updateStatus(po.getPoId(), WorkflowStatus.APPROVED,
                0, "planner@co.com", Instant.now(), null, null, null);

        assertThat(rows).isEqualTo(1);
        PurchaseOrder updated = repository.findById(po.getPoId()).orElseThrow();
        assertThat(updated.getWorkflowStatus()).isEqualTo(WorkflowStatus.APPROVED);
        assertThat(updated.getApprovedBy()).isEqualTo("planner@co.com");
        assertThat(updated.getVersion()).isEqualTo(1);
    }

    @Test
    void updateStatus_withWrongVersion_returns0() {
        PurchaseOrder po = PurchaseOrder.create(rule, 50, new BigDecimal("250.00"),
                WorkflowStatus.PENDING_APPROVAL, null);
        repository.savePurchaseOrder(po);

        int rows = repository.updateStatus(po.getPoId(), WorkflowStatus.APPROVED,
                99, "user", Instant.now(), null, null, null);

        assertThat(rows).isEqualTo(0);
    }

    @Test
    void saveLineItem_persistsLineItem() {
        PurchaseOrder po = PurchaseOrder.create(rule, 100, new BigDecimal("500.00"),
                WorkflowStatus.PENDING_APPROVAL, null);
        repository.savePurchaseOrder(po);

        PoLineItem item = new PoLineItem(UUID.randomUUID(), po.getPoId(),
                "SKU-BEV-001", 100, new BigDecimal("5.00"), new BigDecimal("500.00"));
        repository.saveLineItem(item);

        List<PoLineItem> items = repository.findLineItemsByPoId(po.getPoId());
        assertThat(items).hasSize(1).first().satisfies(i -> {
            assertThat(i.getSkuId()).isEqualTo("SKU-BEV-001");
            assertThat(i.getQuantity()).isEqualTo(100);
            assertThat(i.getLineTotal()).isEqualByComparingTo("500.00");
        });
    }

    @Test
    void findOrders_withStatusFilter_returnsMatching() {
        PurchaseOrder po = PurchaseOrder.create(rule, 50, new BigDecimal("250.00"),
                WorkflowStatus.PENDING_APPROVAL, null);
        repository.savePurchaseOrder(po);

        List<PurchaseOrder> orders = repository.findOrders("PENDING_APPROVAL", null, null, 0, 10);
        assertThat(orders).hasSize(1)
                .first().satisfies(p -> assertThat(p.getWorkflowStatus())
                        .isEqualTo(WorkflowStatus.PENDING_APPROVAL));
    }

    @Test
    void countOrders_withFilter_returnsCorrectCount() {
        PurchaseOrder po = PurchaseOrder.create(rule, 50, new BigDecimal("250.00"),
                WorkflowStatus.PENDING_APPROVAL, null);
        repository.savePurchaseOrder(po);

        assertThat(repository.countOrders("PENDING_APPROVAL", null, null)).isEqualTo(1);
        assertThat(repository.countOrders("APPROVED", null, null)).isEqualTo(0);
    }
}
