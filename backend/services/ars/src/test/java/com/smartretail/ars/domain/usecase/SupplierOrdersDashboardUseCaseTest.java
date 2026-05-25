package com.smartretail.ars.domain.usecase;

import com.smartretail.ars.domain.model.SupplierOrdersDashboard;
import com.smartretail.ars.domain.model.SupplierOrdersDashboard.SupplierOrderEntry;
import com.smartretail.ars.port.outbound.SupplierReadPort;
import com.smartretail.ars.port.outbound.SupplierReadPort.SupplierOrderRow;
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
class SupplierOrdersDashboardUseCaseTest {

    @Mock private SupplierReadPort supplierReadPort;

    private SupplierOrdersDashboardUseCase useCase;

    private static final UUID SPO_ID = UUID.randomUUID();
    private static final UUID PO_ID  = UUID.randomUUID();
    private static final UUID SUP_ID = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        useCase = new SupplierOrdersDashboardUseCase(supplierReadPort);
    }

    @Test
    void assemble_nullStatus_passesNullToPort() {
        when(supplierReadPort.findSupplierOrders(null)).thenReturn(List.of());

        useCase.assemble(null);

        verify(supplierReadPort).findSupplierOrders(null);
    }

    @Test
    void assemble_withStatus_passesStatusToPort() {
        when(supplierReadPort.findSupplierOrders("DISPATCHED")).thenReturn(List.of());

        useCase.assemble("DISPATCHED");

        verify(supplierReadPort).findSupplierOrders("DISPATCHED");
    }

    @Test
    void assemble_emptyList_returnsEmptyDashboard() {
        when(supplierReadPort.findSupplierOrders(null)).thenReturn(List.of());

        SupplierOrdersDashboard result = useCase.assemble(null);

        assertThat(result.orders()).isEmpty();
        assertThat(result.dataFreshness()).isNotNull();
    }

    @Test
    void assemble_mapsAllFieldsCorrectly() {
        Instant confirmed = Instant.parse("2026-05-14T09:00:00Z");
        Instant dispatched = Instant.parse("2026-05-16T08:00:00Z");
        Instant lastUpdate = Instant.parse("2026-05-16T08:00:00Z");
        LocalDate eta = LocalDate.of(2026, 5, 20);

        SupplierOrderRow row = new SupplierOrderRow(
                SPO_ID, PO_ID, SUP_ID, "Acme Beverages Ltd",
                "SKU-BEV-001", "DC-LONDON", 500, "DISPATCHED",
                confirmed, dispatched, eta, lastUpdate
        );
        when(supplierReadPort.findSupplierOrders(null)).thenReturn(List.of(row));

        SupplierOrdersDashboard result = useCase.assemble(null);

        assertThat(result.orders()).hasSize(1);
        SupplierOrderEntry entry = result.orders().getFirst();
        assertThat(entry.supplierPoId()).isEqualTo(SPO_ID);
        assertThat(entry.poId()).isEqualTo(PO_ID);
        assertThat(entry.supplierId()).isEqualTo(SUP_ID);
        assertThat(entry.supplierName()).isEqualTo("Acme Beverages Ltd");
        assertThat(entry.skuId()).isEqualTo("SKU-BEV-001");
        assertThat(entry.dcId()).isEqualTo("DC-LONDON");
        assertThat(entry.quantity()).isEqualTo(500);
        assertThat(entry.shipmentStatus()).isEqualTo("DISPATCHED");
        assertThat(entry.confirmedAt()).isEqualTo(confirmed);
        assertThat(entry.dispatchedAt()).isEqualTo(dispatched);
        assertThat(entry.eta()).isEqualTo(eta);
        assertThat(entry.lastUpdateAt()).isEqualTo(lastUpdate);
    }

    @Test
    void assemble_nullableTimestamps_arePreserved() {
        SupplierOrderRow row = new SupplierOrderRow(
                SPO_ID, PO_ID, SUP_ID, "Beta Supplier",
                "SKU-DRY-002", "DC-MANCH", 200, "PENDING",
                null, null, null, null
        );
        when(supplierReadPort.findSupplierOrders("PENDING")).thenReturn(List.of(row));

        SupplierOrdersDashboard result = useCase.assemble("PENDING");

        SupplierOrderEntry entry = result.orders().getFirst();
        assertThat(entry.confirmedAt()).isNull();
        assertThat(entry.dispatchedAt()).isNull();
        assertThat(entry.eta()).isNull();
        assertThat(entry.lastUpdateAt()).isNull();
    }

    @Test
    void assemble_multipleOrders_preservesOrder() {
        UUID spo1 = UUID.randomUUID();
        UUID spo2 = UUID.randomUUID();
        SupplierOrderRow r1 = new SupplierOrderRow(
                spo1, PO_ID, SUP_ID, "Alpha", "SKU-001", "DC-A", 100, "EXCEPTION",
                null, null, null, null);
        SupplierOrderRow r2 = new SupplierOrderRow(
                spo2, PO_ID, SUP_ID, "Beta", "SKU-002", "DC-B", 200, "CONFIRMED",
                null, null, LocalDate.of(2026, 5, 22), null);
        when(supplierReadPort.findSupplierOrders(null)).thenReturn(List.of(r1, r2));

        SupplierOrdersDashboard result = useCase.assemble(null);

        assertThat(result.orders()).hasSize(2);
        assertThat(result.orders().get(0).supplierPoId()).isEqualTo(spo1);
        assertThat(result.orders().get(1).supplierPoId()).isEqualTo(spo2);
    }

    @Test
    void assemble_dataFreshnessIsAfterTestStart() {
        Instant before = Instant.now();
        when(supplierReadPort.findSupplierOrders(null)).thenReturn(List.of());

        SupplierOrdersDashboard result = useCase.assemble(null);

        assertThat(result.dataFreshness()).isAfterOrEqualTo(before);
    }
}
