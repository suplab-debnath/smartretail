package com.smartretail.sis.adapter.outbound.persistence;

import com.smartretail.sis.domain.model.SalesTransaction;
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
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest(properties = "spring.jpa.hibernate.ddl-auto=none")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers(disabledWithoutDocker = true)
@Import(SalesEventRepository.class)
class SalesEventRepositoryTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15")
            .withInitScript("sql/schema-sis.sql");

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    private SalesEventRepository repository;

    @Autowired
    private SalesEventJpaRepository jpaRepository;

    @Test
    void save_persistsSalesTransaction() {
        UUID txId = UUID.randomUUID();
        SalesTransaction tx = new SalesTransaction(
                txId, "STORE-001", "SKU-BEV-001", "DC-LONDON",
                30, BigDecimal.valueOf(8.50), SalesTransaction.Channel.POS,
                Instant.parse("2026-05-15T14:23:00Z"));

        repository.save(tx, "s3://bucket/raw.json");

        SalesEventId id = new SalesEventId(txId, LocalDate.of(2026, 5, 15));
        assertThat(jpaRepository.findById(id)).isPresent().hasValueSatisfying(e -> {
            assertThat(e.getStoreId()).isEqualTo("STORE-001");
            assertThat(e.getSkuId()).isEqualTo("SKU-BEV-001");
            assertThat(e.getDcId()).isEqualTo("DC-LONDON");
            assertThat(e.getQuantity()).isEqualTo(30);
            assertThat(e.getUnitPrice()).isEqualByComparingTo("8.50");
            assertThat(e.getChannel()).isEqualTo("POS");
            assertThat(e.getRawS3Reference()).isEqualTo("s3://bucket/raw.json");
            assertThat(e.getEventTimestamp()).isEqualTo(Instant.parse("2026-05-15T14:23:00Z"));
        });
    }

    @Test
    void save_ecommerceChannel_persistsCorrectly() {
        SalesTransaction tx = new SalesTransaction(
                UUID.randomUUID(), "STORE-002", "SKU-BEV-002", "DC-MANCHESTER",
                5, BigDecimal.valueOf(2.99), SalesTransaction.Channel.ECOMMERCE,
                Instant.parse("2026-05-15T10:00:00Z"));

        repository.save(tx, null);

        assertThat(jpaRepository.count()).isPositive();
        SalesEventId id = new SalesEventId(tx.transactionId(), LocalDate.of(2026, 5, 15));
        assertThat(jpaRepository.findById(id)).isPresent()
                .hasValueSatisfying(e -> assertThat(e.getChannel()).isEqualTo("ECOMMERCE"));
    }

    @Test
    void save_nullRawS3Reference_persistsCorrectly() {
        SalesTransaction tx = new SalesTransaction(
                UUID.randomUUID(), "STORE-003", "SKU-BEV-003", "DC-LONDON",
                10, BigDecimal.ONE, SalesTransaction.Channel.POS,
                Instant.parse("2026-05-16T08:00:00Z"));

        repository.save(tx, null);

        SalesEventId id = new SalesEventId(tx.transactionId(), LocalDate.of(2026, 5, 16));
        assertThat(jpaRepository.findById(id)).isPresent()
                .hasValueSatisfying(e -> assertThat(e.getRawS3Reference()).isNull());
    }

    @Test
    void save_eventDateDerivedFromTimestamp() {
        // Timestamp at midnight UTC — event_date should be the UTC date
        Instant midnight = Instant.parse("2026-06-01T00:00:00Z");
        SalesTransaction tx = new SalesTransaction(
                UUID.randomUUID(), "STORE-001", "SKU-001", "DC-LONDON",
                1, BigDecimal.ONE, SalesTransaction.Channel.POS, midnight);

        repository.save(tx, null);

        SalesEventId id = new SalesEventId(tx.transactionId(), LocalDate.of(2026, 6, 1));
        assertThat(jpaRepository.findById(id)).isPresent();
    }
}
