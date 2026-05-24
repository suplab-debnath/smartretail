package com.smartretail.sup.domain.usecase;

import com.smartretail.sup.domain.model.SupplierRecordList;
import com.smartretail.sup.domain.model.SupplierRecordList.SupplierEntry;
import com.smartretail.sup.port.outbound.SupplierReadPort;
import com.smartretail.sup.port.outbound.SupplierReadPort.SupplierRow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SupplierQueryUseCaseTest {

    @Mock private SupplierReadPort supplierReadPort;

    private SupplierQueryUseCase useCase;

    @BeforeEach
    void setUp() {
        useCase = new SupplierQueryUseCase(supplierReadPort);
    }

    @Test
    void getSuppliers_returnsMappedEntries() {
        UUID id1 = UUID.randomUUID();
        UUID id2 = UUID.randomUUID();
        when(supplierReadPort.findAllSuppliers()).thenReturn(List.of(
                new SupplierRow(id1, "Acme Beverages"),
                new SupplierRow(id2, "Fresh Dairy Co")));

        SupplierRecordList result = useCase.getSuppliers();

        assertThat(result.suppliers()).hasSize(2);
        assertThat(result.suppliers()).extracting(SupplierEntry::supplierId)
                .containsExactly(id1, id2);
        assertThat(result.suppliers()).extracting(SupplierEntry::supplierName)
                .containsExactly("Acme Beverages", "Fresh Dairy Co");
    }

    @Test
    void getSuppliers_emptyRepository_returnsEmptyList() {
        when(supplierReadPort.findAllSuppliers()).thenReturn(List.of());

        SupplierRecordList result = useCase.getSuppliers();

        assertThat(result.suppliers()).isEmpty();
    }

    @Test
    void getSuppliers_singleEntry_mapsCorrectly() {
        UUID id = UUID.randomUUID();
        when(supplierReadPort.findAllSuppliers()).thenReturn(
                List.of(new SupplierRow(id, "Test Supplier")));

        SupplierRecordList result = useCase.getSuppliers();

        SupplierEntry entry = result.suppliers().getFirst();
        assertThat(entry.supplierId()).isEqualTo(id);
        assertThat(entry.supplierName()).isEqualTo("Test Supplier");
    }
}
