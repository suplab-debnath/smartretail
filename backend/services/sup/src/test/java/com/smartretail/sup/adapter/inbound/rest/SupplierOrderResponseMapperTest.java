package com.smartretail.sup.adapter.inbound.rest;

import com.smartretail.sup.adapter.in.web.generated.model.SupplierOrderListResponse;
import com.smartretail.sup.domain.model.SupplierOrderList;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class SupplierOrderResponseMapperTest {

    private final SupplierOrderResponseMapper mapper = Mappers.getMapper(SupplierOrderResponseMapper.class);

    @Test
    void shouldMapOrderWithAllOptionalTimestampsPresent() {
        var order = new SupplierOrderList.SupplierOrder(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "Acme Beverages", "SKU-BEV-001", "DC-LONDON", 500, "DISPATCHED",
                Instant.parse("2026-05-01T10:00:00Z"),
                Instant.parse("2026-05-03T08:00:00Z"),
                LocalDate.of(2026, 5, 10),
                Instant.parse("2026-05-03T09:00:00Z"));
        SupplierOrderList list = new SupplierOrderList(List.of(order), Instant.now());

        SupplierOrderListResponse response = mapper.toResponse(list);

        assertThat(response.getOrders()).hasSize(1);
        assertThat(response.getOrders().get(0).getConfirmedAt()).isNotNull();
        assertThat(response.getOrders().get(0).getDispatchedAt()).isNotNull();
        assertThat(response.getOrders().get(0).getEta()).isEqualTo(LocalDate.of(2026, 5, 10));
        assertThat(response.getOrders().get(0).getLastUpdateAt()).isNotNull();
    }

    @Test
    void shouldMapOrderWithAllOptionalTimestampsNull() {
        var order = new SupplierOrderList.SupplierOrder(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "Acme Beverages", "SKU-BEV-001", "DC-LONDON", 200, "PENDING",
                null, null, null, null);
        SupplierOrderList list = new SupplierOrderList(List.of(order), Instant.now());

        SupplierOrderListResponse response = mapper.toResponse(list);

        assertThat(response.getOrders()).hasSize(1);
        assertThat(response.getOrders().get(0).getConfirmedAt()).isNull();
        assertThat(response.getOrders().get(0).getDispatchedAt()).isNull();
        assertThat(response.getOrders().get(0).getEta()).isNull();
        assertThat(response.getOrders().get(0).getLastUpdateAt()).isNull();
    }

    @Test
    void shouldMapOrderWithConfirmedAtOnlySet() {
        var order = new SupplierOrderList.SupplierOrder(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "Acme Beverages", "SKU-BEV-001", "DC-LONDON", 100, "CONFIRMED",
                Instant.parse("2026-05-02T12:00:00Z"),
                null, null, null);
        SupplierOrderList list = new SupplierOrderList(List.of(order), Instant.now());

        SupplierOrderListResponse response = mapper.toResponse(list);

        assertThat(response.getOrders().get(0).getConfirmedAt()).isNotNull();
        assertThat(response.getOrders().get(0).getDispatchedAt()).isNull();
        assertThat(response.getOrders().get(0).getEta()).isNull();
        assertThat(response.getOrders().get(0).getLastUpdateAt()).isNull();
    }

    @Test
    void shouldMapEmptyOrderList() {
        SupplierOrderList list = new SupplierOrderList(List.of(), Instant.now());

        SupplierOrderListResponse response = mapper.toResponse(list);

        assertThat(response.getOrders()).isEmpty();
        assertThat(response.getDataFreshness()).isNotNull();
    }
}
