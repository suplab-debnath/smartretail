package com.smartretail.pps.domain.usecase;

import com.smartretail.pps.domain.model.PromotionList;
import com.smartretail.pps.domain.model.PromotionList.PromotionSchedule;
import com.smartretail.pps.port.outbound.PromotionReadPort;
import com.smartretail.pps.port.outbound.PromotionReadPort.PromotionRow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PromotionQueryUseCaseTest {

    @Mock
    private PromotionReadPort promotionReadPort;

    private PromotionQueryUseCase useCase;

    private static final Instant FRESHNESS = Instant.parse("2026-05-18T12:00:00Z");

    private static final PromotionRow ROW = new PromotionRow(
            UUID.fromString("aa000001-0000-0000-0000-000000000001"),
            "Summer Beverage Blitz",
            List.of("SKU-BEV-001", "SKU-BEV-002"),
            List.of("DC-LONDON", "DC-MANCHESTER"),
            15.0,
            1.35,
            -1.2,
            LocalDate.of(2026, 6, 1),
            LocalDate.of(2026, 6, 14),
            "ACTIVE",
            UUID.fromString("bb000001-0000-0000-0000-000000000001")
    );

    @BeforeEach
    void setUp() {
        useCase = new PromotionQueryUseCase(promotionReadPort);
    }

    @Test
    void getPromotionSchedules_withNullStatus_returnsAllSchedules() {
        when(promotionReadPort.findPromotionSchedules(null)).thenReturn(List.of(ROW));
        when(promotionReadPort.findDataFreshness()).thenReturn(FRESHNESS);

        PromotionList result = useCase.getPromotionSchedules(null);

        assertThat(result.schedules()).hasSize(1);
        assertThat(result.dataFreshness()).isEqualTo(FRESHNESS);
        verify(promotionReadPort).findPromotionSchedules(null);
    }

    @Test
    void getPromotionSchedules_withStatusFilter_passesFilterToRepository() {
        when(promotionReadPort.findPromotionSchedules("ACTIVE")).thenReturn(List.of(ROW));
        when(promotionReadPort.findDataFreshness()).thenReturn(FRESHNESS);

        PromotionList result = useCase.getPromotionSchedules("ACTIVE");

        assertThat(result.schedules()).hasSize(1);
        verify(promotionReadPort).findPromotionSchedules("ACTIVE");
    }

    @Test
    void getPromotionSchedules_mapsAllFieldsFromRowToSchedule() {
        when(promotionReadPort.findPromotionSchedules(null)).thenReturn(List.of(ROW));
        when(promotionReadPort.findDataFreshness()).thenReturn(FRESHNESS);

        PromotionSchedule schedule = useCase.getPromotionSchedules(null).schedules().get(0);

        assertThat(schedule.promotionId()).isEqualTo(ROW.promotionId());
        assertThat(schedule.promotionName()).isEqualTo("Summer Beverage Blitz");
        assertThat(schedule.skuIds()).containsExactly("SKU-BEV-001", "SKU-BEV-002");
        assertThat(schedule.dcIds()).containsExactly("DC-LONDON", "DC-MANCHESTER");
        assertThat(schedule.discountPct()).isEqualTo(15.0);
        assertThat(schedule.upliftFactor()).isEqualTo(1.35);
        assertThat(schedule.elasticityCoeff()).isEqualTo(-1.2);
        assertThat(schedule.validFrom()).isEqualTo(LocalDate.of(2026, 6, 1));
        assertThat(schedule.validTo()).isEqualTo(LocalDate.of(2026, 6, 14));
        assertThat(schedule.status()).isEqualTo("ACTIVE");
        assertThat(schedule.sourceEventId()).isEqualTo(ROW.sourceEventId());
    }

    @Test
    void getPromotionSchedules_withEmptyRepository_returnsEmptyList() {
        when(promotionReadPort.findPromotionSchedules("EXPIRED")).thenReturn(List.of());
        when(promotionReadPort.findDataFreshness()).thenReturn(FRESHNESS);

        PromotionList result = useCase.getPromotionSchedules("EXPIRED");

        assertThat(result.schedules()).isEmpty();
        assertThat(result.dataFreshness()).isEqualTo(FRESHNESS);
    }

    @Test
    void getPromotionSchedules_withNullElasticityCoeff_mapsNullCorrectly() {
        PromotionRow rowWithNullElasticity = new PromotionRow(
                ROW.promotionId(), ROW.promotionName(), ROW.skuIds(), ROW.dcIds(),
                ROW.discountPct(), ROW.upliftFactor(),
                null, // elasticityCoeff is optional
                ROW.validFrom(), ROW.validTo(), ROW.status(), ROW.sourceEventId()
        );
        when(promotionReadPort.findPromotionSchedules(null)).thenReturn(List.of(rowWithNullElasticity));
        when(promotionReadPort.findDataFreshness()).thenReturn(FRESHNESS);

        PromotionSchedule schedule = useCase.getPromotionSchedules(null).schedules().get(0);

        assertThat(schedule.elasticityCoeff()).isNull();
    }

    @Test
    void getPromotionSchedules_withMultipleRows_returnsAllMapped() {
        PromotionRow row2 = new PromotionRow(
                UUID.fromString("aa000002-0000-0000-0000-000000000002"),
                "Winter Snack Deal",
                List.of("SKU-SNK-001"),
                List.of("DC-LONDON"),
                10.0, 1.2, null,
                LocalDate.of(2026, 12, 1),
                LocalDate.of(2026, 12, 31),
                "ACTIVE",
                null
        );
        when(promotionReadPort.findPromotionSchedules(null)).thenReturn(List.of(ROW, row2));
        when(promotionReadPort.findDataFreshness()).thenReturn(FRESHNESS);

        PromotionList result = useCase.getPromotionSchedules(null);

        assertThat(result.schedules()).hasSize(2);
        assertThat(result.schedules().get(1).promotionName()).isEqualTo("Winter Snack Deal");
    }
}
