package com.smartretail.sup.domain.usecase;

import com.smartretail.sup.domain.model.SupplierOrderList;
import com.smartretail.sup.domain.model.SupplierOrderList.SupplierOrder;
import com.smartretail.sup.port.outbound.SupplierOrderReadPort;
import com.smartretail.sup.port.outbound.SupplierOrderReadPort.SupplierOrderRow;
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
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SupplierOrderQueryUseCaseTest {

    @Mock private SupplierOrderReadPort supplierOrderReadPort;

    private SupplierOrderQueryUseCase useCase;

    @BeforeEach
    void setUp() {
        useCase = new SupplierOrderQueryUseCase(supplierOrderReadPort);
    }

    @Test
    void getSupplierOrders_returnsOrdersMappedFromRows() {
        Instant now = Instant.now();
        SupplierOrderRow row = sampleRow(UUID.randomUUID(), UUID.randomUUID(), "SHIPPED");
        when(supplierOrderReadPort.findSupplierOrders("SHIPPED")).thenReturn(List.of(row));
        when(supplierOrderReadPort.findDataFreshness()).thenReturn(now);

        SupplierOrderList result = useCase.getSupplierOrders("SHIPPED");

        assertThat(result.orders()).hasSize(1);
        assertThat(result.dataFreshness()).isEqualTo(now);

        SupplierOrder order = result.orders().getFirst();
        assertThat(order.supplierPoId()).isEqualTo(row.supplierPoId());
        assertThat(order.poId()).isEqualTo(row.poId());
        assertThat(order.skuId()).isEqualTo("SKU-BEV-001");
        assertThat(order.shipmentStatus()).isEqualTo("SHIPPED");
    }

    @Test
    void getSupplierOrders_noFilter_returnsAll() {
        Instant freshness = Instant.now();
        when(supplierOrderReadPort.findSupplierOrders(null)).thenReturn(List.of(
                sampleRow(UUID.randomUUID(), UUID.randomUUID(), "PENDING"),
                sampleRow(UUID.randomUUID(), UUID.randomUUID(), "SHIPPED")
        ));
        when(supplierOrderReadPort.findDataFreshness()).thenReturn(freshness);

        SupplierOrderList result = useCase.getSupplierOrders(null);

        assertThat(result.orders()).hasSize(2);
    }

    @Test
    void getSupplierOrders_emptyResult_returnsEmptyList() {
        when(supplierOrderReadPort.findSupplierOrders("CANCELLED")).thenReturn(List.of());
        when(supplierOrderReadPort.findDataFreshness()).thenReturn(Instant.now());

        SupplierOrderList result = useCase.getSupplierOrders("CANCELLED");

        assertThat(result.orders()).isEmpty();
    }

    @Test
    void getSupplierOrders_nullTimestampsPreserved() {
        SupplierOrderRow row = new SupplierOrderRow(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "Supplier A", "SKU-001", "DC-LONDON", 100,
                "PENDING", null, null, null, null);
        when(supplierOrderReadPort.findSupplierOrders(null)).thenReturn(List.of(row));
        when(supplierOrderReadPort.findDataFreshness()).thenReturn(Instant.now());

        SupplierOrderList result = useCase.getSupplierOrders(null);

        SupplierOrder order = result.orders().getFirst();
        assertThat(order.confirmedAt()).isNull();
        assertThat(order.dispatchedAt()).isNull();
        assertThat(order.eta()).isNull();
        assertThat(order.lastUpdateAt()).isNull();
    }

    @Test
    void getSupplierOrders_dataFreshnessFromRepository() {
        Instant expected = Instant.parse("2026-05-17T12:00:00Z");
        when(supplierOrderReadPort.findSupplierOrders(null)).thenReturn(List.of());
        when(supplierOrderReadPort.findDataFreshness()).thenReturn(expected);

        SupplierOrderList result = useCase.getSupplierOrders(null);

        assertThat(result.dataFreshness()).isEqualTo(expected);
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private SupplierOrderRow sampleRow(UUID supplierPoId, UUID poId, String status) {
        return new SupplierOrderRow(
                supplierPoId, poId, UUID.randomUUID(),
                "Test Supplier", "SKU-BEV-001", "DC-LONDON", 50,
                status,
                Instant.parse("2026-05-10T09:00:00Z"),
                Instant.parse("2026-05-12T10:00:00Z"),
                LocalDate.of(2026, 5, 20),
                Instant.parse("2026-05-14T11:00:00Z"));
    }
}
