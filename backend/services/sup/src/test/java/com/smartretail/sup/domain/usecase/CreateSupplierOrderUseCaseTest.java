package com.smartretail.sup.domain.usecase;

import com.smartretail.sup.port.inbound.CreateSupplierOrderPort.Command;
import com.smartretail.sup.port.outbound.SupplierOrderWritePort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CreateSupplierOrderUseCaseTest {

    @Mock private SupplierOrderWritePort supplierOrderWritePort;

    private CreateSupplierOrderUseCase useCase;

    @BeforeEach
    void setUp() {
        useCase = new CreateSupplierOrderUseCase(supplierOrderWritePort);
    }

    @Test
    void createSupplierOrder_delegatesToWritePort_andReturnsGeneratedId() {
        UUID poId         = UUID.randomUUID();
        UUID supplierId   = UUID.randomUUID();
        UUID generatedId  = UUID.randomUUID();
        Command command   = new Command(poId, supplierId, "SKU-BEV-001", "DC-LONDON", 100);

        when(supplierOrderWritePort.insertSupplierOrder(poId, supplierId, "SKU-BEV-001", "DC-LONDON", 100))
                .thenReturn(generatedId);

        UUID result = useCase.createSupplierOrder(command);

        assertThat(result).isEqualTo(generatedId);
        verify(supplierOrderWritePort).insertSupplierOrder(poId, supplierId, "SKU-BEV-001", "DC-LONDON", 100);
    }

    @Test
    void createSupplierOrder_forwardsAllCommandFields() {
        UUID poId       = UUID.randomUUID();
        UUID supplierId = UUID.randomUUID();
        Command command = new Command(poId, supplierId, "SKU-DAIRY-002", "DC-BIRMINGHAM", 250);

        when(supplierOrderWritePort.insertSupplierOrder(poId, supplierId, "SKU-DAIRY-002", "DC-BIRMINGHAM", 250))
                .thenReturn(UUID.randomUUID());

        useCase.createSupplierOrder(command);

        verify(supplierOrderWritePort).insertSupplierOrder(poId, supplierId, "SKU-DAIRY-002", "DC-BIRMINGHAM", 250);
    }

    @Test
    void createSupplierOrder_propagatesDuplicatePoException() {
        UUID poId     = UUID.randomUUID();
        UUID supplierId = UUID.randomUUID();
        Command command = new Command(poId, supplierId, "SKU-001", "DC-LONDON", 50);

        when(supplierOrderWritePort.insertSupplierOrder(poId, supplierId, "SKU-001", "DC-LONDON", 50))
                .thenThrow(new SupplierOrderWritePort.DuplicatePoException(poId));

        assertThatThrownBy(() -> useCase.createSupplierOrder(command))
                .isInstanceOf(SupplierOrderWritePort.DuplicatePoException.class)
                .hasMessageContaining(poId.toString());
    }
}
