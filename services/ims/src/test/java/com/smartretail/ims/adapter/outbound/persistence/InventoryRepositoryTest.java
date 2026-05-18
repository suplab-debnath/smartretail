package com.smartretail.ims.adapter.outbound.persistence;

import com.smartretail.ims.domain.model.AlertSeverity;
import com.smartretail.ims.domain.model.AlertType;
import com.smartretail.ims.domain.model.InventoryPosition;
import com.smartretail.ims.domain.model.StockAlert;
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

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest(properties = "spring.jpa.hibernate.ddl-auto=none")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers(disabledWithoutDocker = true)
@Import(InventoryRepository.class)
class InventoryRepositoryTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15")
            .withInitScript("sql/schema-ims.sql");

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    private InventoryRepository repository;
    @Autowired
    private InventoryPositionJpaRepository posRepo;

    private UUID positionId;

    @BeforeEach
    void seedPosition() {
        // Insert a position directly via the JPA repo so tests have something to work
        // with
        positionId = UUID.randomUUID();
        var entity = new InventoryPositionJpaEntity();
        setField(entity, "positionId", positionId);
        setField(entity, "skuId", "SKU-BEV-001");
        setField(entity, "dcId", "DC-LONDON");
        setField(entity, "onHand", 200);
        setField(entity, "inTransit", 50);
        setField(entity, "reserved", 10);
        setField(entity, "reorderPoint", 100);
        setField(entity, "safetyStock", 20);
        setField(entity, "version", 0);
        setField(entity, "lastUpdatedAt", java.time.Instant.now());
        posRepo.save(entity);
    }

    // Reflection helper — entity has protected constructor and no public setters
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
    void findBySkuAndDc_returnsPosition() {
        Optional<InventoryPosition> result = repository.findBySkuAndDc("SKU-BEV-001", "DC-LONDON");
        assertThat(result).isPresent().hasValueSatisfying(pos -> {
            assertThat(pos.getPositionId()).isEqualTo(positionId);
            assertThat(pos.getOnHand()).isEqualTo(200);
            assertThat(pos.getReorderPoint()).isEqualTo(100);
            assertThat(pos.getVersion()).isEqualTo(0);
        });
    }

    @Test
    void findBySkuAndDc_unknownSku_returnsEmpty() {
        assertThat(repository.findBySkuAndDc("UNKNOWN", "DC-LONDON")).isEmpty();
    }

    @Test
    void findById_returnsPosition() {
        Optional<InventoryPosition> result = repository.findById(positionId);
        assertThat(result).isPresent()
                .hasValueSatisfying(pos -> assertThat(pos.getSkuId()).isEqualTo("SKU-BEV-001"));
    }

    @Test
    void decrementOnHand_success_returns1AndUpdatesStock() {
        int rowsUpdated = repository.decrementOnHand(positionId, 30, 0);
        assertThat(rowsUpdated).isEqualTo(1);

        InventoryPosition pos = repository.findById(positionId).orElseThrow();
        assertThat(pos.getOnHand()).isEqualTo(170);
        assertThat(pos.getVersion()).isEqualTo(1);
    }

    @Test
    void decrementOnHand_wrongVersion_returns0() {
        int rowsUpdated = repository.decrementOnHand(positionId, 30, 99);
        assertThat(rowsUpdated).isEqualTo(0);
    }

    @Test
    void decrementOnHand_quantityExceedsOnHand_returns0() {
        int rowsUpdated = repository.decrementOnHand(positionId, 500, 0);
        assertThat(rowsUpdated).isEqualTo(0);
    }

    @Test
    void saveAlert_persistsAlert() {
        InventoryPosition pos = repository.findById(positionId).orElseThrow();
        StockAlert alert = StockAlert.create(pos, AlertType.LOW_STOCK, AlertSeverity.HIGH);

        repository.saveAlert(alert);

        List<StockAlert> alerts = repository.findAlerts("DC-LONDON", null, "ACTIVE", 0, 10);
        assertThat(alerts).hasSize(1).first().satisfies(a -> {
            assertThat(a.getSkuId()).isEqualTo("SKU-BEV-001");
            assertThat(a.getDcId()).isEqualTo("DC-LONDON");
            assertThat(a.getAlertType()).isEqualTo(AlertType.LOW_STOCK);
            assertThat(a.getSeverity()).isEqualTo(AlertSeverity.HIGH);
            assertThat(a.getStatus()).isEqualTo("ACTIVE");
        });
    }

    @Test
    void findPositions_withDcFilter_returnsMatching() {
        List<InventoryPosition> positions = repository.findPositions("DC-LONDON", null, 0, 10);
        assertThat(positions).hasSize(1)
                .first().satisfies(p -> assertThat(p.getDcId()).isEqualTo("DC-LONDON"));
    }

    @Test
    void findPositions_withSkuFilter_returnsMatching() {
        List<InventoryPosition> positions = repository.findPositions(null, "SKU-BEV-001", 0, 10);
        assertThat(positions).hasSize(1);
    }

    @Test
    void findPositions_noFilters_returnsAll() {
        List<InventoryPosition> positions = repository.findPositions(null, null, 0, 10);
        assertThat(positions).hasSize(1);
    }

    @Test
    void countPositions_withFilter_returnsCorrectCount() {
        assertThat(repository.countPositions("DC-LONDON", null)).isEqualTo(1);
        assertThat(repository.countPositions("DC-TOKYO", null)).isEqualTo(0);
    }

    @Test
    void countAlerts_withFilter_returnsCorrectCount() {
        InventoryPosition pos = repository.findById(positionId).orElseThrow();
        repository.saveAlert(StockAlert.create(pos, AlertType.LOW_STOCK, AlertSeverity.CRITICAL));

        assertThat(repository.countAlerts("DC-LONDON", null, "ACTIVE")).isEqualTo(1);
        assertThat(repository.countAlerts("DC-TOKYO", null, null)).isEqualTo(0);
    }
}
