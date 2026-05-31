package com.smartretail.pps.adapter.outbound.persistence;

import com.smartretail.pps.domain.model.PromotionActivationCommand;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcOperations;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PromotionWriteRepositoryTest {

    @Mock
    private NamedParameterJdbcOperations jdbc;

    private PromotionWriteRepository repository;

    @BeforeEach
    void setUp() {
        repository = new PromotionWriteRepository(jdbc);
    }

    @Test
    void upsert_callsJdbcUpdateWithCorrectParams() {
        UUID promotionId = UUID.randomUUID();
        PromotionActivationCommand command = new PromotionActivationCommand(
                promotionId,
                "Summer Beverages Promo",
                List.of("SKU-BEV-001", "SKU-BEV-002"),
                BigDecimal.valueOf(15.0),
                LocalDate.of(2026, 7, 1),
                LocalDate.of(2026, 7, 31));

        when(jdbc.update(anyString(), any(MapSqlParameterSource.class))).thenReturn(1);

        repository.upsert(command);

        ArgumentCaptor<MapSqlParameterSource> captor =
                ArgumentCaptor.forClass(MapSqlParameterSource.class);
        verify(jdbc).update(contains("INSERT INTO promotions.promotion_schedules"), captor.capture());

        MapSqlParameterSource params = captor.getValue();
        assertThat(params.getValue("promotionId")).isEqualTo(promotionId.toString());
        assertThat(params.getValue("promotionName")).isEqualTo("Summer Beverages Promo");
        assertThat((BigDecimal) params.getValue("discountPct")).isEqualByComparingTo(BigDecimal.valueOf(15.0));
    }

    @Test
    void upsert_skuIdsArrayContainsBothSkus() {
        UUID promotionId = UUID.randomUUID();
        UUID skuA = UUID.randomUUID();
        UUID skuB = UUID.randomUUID();
        PromotionActivationCommand command = new PromotionActivationCommand(
                promotionId, "Test Promo",
                List.of(skuA.toString(), skuB.toString()),
                BigDecimal.valueOf(10.0),
                LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 30));

        when(jdbc.update(anyString(), any(MapSqlParameterSource.class))).thenReturn(1);

        repository.upsert(command);

        ArgumentCaptor<MapSqlParameterSource> captor =
                ArgumentCaptor.forClass(MapSqlParameterSource.class);
        verify(jdbc).update(anyString(), captor.capture());

        String skuIds = (String) captor.getValue().getValue("skuIds");
        assertThat(skuIds).startsWith("{").endsWith("}");
        assertThat(skuIds).contains(skuA.toString());
        assertThat(skuIds).contains(skuB.toString());
    }

    @Test
    void upsert_onConflict_sendsUpsertSql() {
        PromotionActivationCommand command = new PromotionActivationCommand(
                UUID.randomUUID(), "Conflict Promo",
                List.of("SKU-X"), BigDecimal.valueOf(5.0),
                LocalDate.now(), LocalDate.now().plusDays(7));

        when(jdbc.update(anyString(), any(MapSqlParameterSource.class))).thenReturn(0); // ON CONFLICT DO UPDATE returns 0 affected rows

        repository.upsert(command); // should not throw

        verify(jdbc).update(contains("ON CONFLICT"), any(MapSqlParameterSource.class));
    }

    // ── toPostgresUuidArray static helper ────────────────────────────────────

    @Test
    void toPostgresUuidArray_emptyArray_returnsEmptyBraces() {
        assertThat(PromotionWriteRepository.toPostgresUuidArray(new String[0])).isEqualTo("{}");
    }

    @Test
    void toPostgresUuidArray_nullArray_returnsEmptyBraces() {
        assertThat(PromotionWriteRepository.toPostgresUuidArray(null)).isEqualTo("{}");
    }

    @Test
    void toPostgresUuidArray_singleElement_wrapsInBraces() {
        String uuid = UUID.randomUUID().toString();
        String result = PromotionWriteRepository.toPostgresUuidArray(new String[]{uuid});
        assertThat(result).isEqualTo("{\"" + uuid + "\"}");
    }

    @Test
    void toPostgresUuidArray_twoElements_separatedByComma() {
        String a = UUID.randomUUID().toString();
        String b = UUID.randomUUID().toString();
        String result = PromotionWriteRepository.toPostgresUuidArray(new String[]{a, b});
        assertThat(result).isEqualTo("{\"" + a + "\",\"" + b + "\"}");
    }
}
