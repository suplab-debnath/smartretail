package com.smartretail.sup.adapter.outbound.persistence;

import com.smartretail.sup.port.outbound.SupplierOrderWritePort.DuplicatePoException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcOperations;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SupplierOrderRepositoryTest {

    @Mock
    private NamedParameterJdbcOperations jdbc;

    private SupplierOrderRepository repository;

    @BeforeEach
    void setUp() {
        repository = new SupplierOrderRepository(jdbc);
    }

    @Test
    void insertSupplierOrder_success_returnsNewSupplierPoId() {
        UUID poId = UUID.randomUUID();
        UUID supplierId = UUID.randomUUID();

        UUID result = repository.insertSupplierOrder(poId, supplierId, "SKU-BEV-001", "DC-LONDON", 500);

        assertThat(result).isNotNull();

        ArgumentCaptor<MapSqlParameterSource> captor =
                ArgumentCaptor.forClass(MapSqlParameterSource.class);
        verify(jdbc).update(contains("INSERT INTO supplier.supplier_pos"), captor.capture());

        MapSqlParameterSource params = captor.getValue();
        assertThat(params.getValue("poId")).isEqualTo(poId.toString());
        assertThat(params.getValue("supplierId")).isEqualTo(supplierId.toString());
        assertThat(params.getValue("skuId")).isEqualTo("SKU-BEV-001");
        assertThat(params.getValue("dcId")).isEqualTo("DC-LONDON");
        assertThat(params.getValue("quantity")).isEqualTo(500);
    }

    @Test
    void insertSupplierOrder_duplicateKey_throwsDuplicatePoException() {
        UUID poId = UUID.randomUUID();
        UUID supplierId = UUID.randomUUID();
        doThrow(new DuplicateKeyException("duplicate key"))
                .when(jdbc).update(anyString(), any(MapSqlParameterSource.class));

        assertThatThrownBy(() ->
                repository.insertSupplierOrder(poId, supplierId, "SKU-BEV-001", "DC-LONDON", 100))
                .isInstanceOf(DuplicatePoException.class)
                .hasMessageContaining(poId.toString());
    }

    @Test
    @SuppressWarnings("unchecked")
    void findSupplierOrders_nullStatus_queriesWithNullStatus() {
        when(jdbc.query(anyString(), any(MapSqlParameterSource.class), any(RowMapper.class)))
                .thenReturn(List.of());

        var result = repository.findSupplierOrders(null);

        assertThat(result).isEmpty();
        verify(jdbc).query(contains("SELECT"), any(MapSqlParameterSource.class), any(RowMapper.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void findAllSuppliers_queriesSupplierRecords() {
        when(jdbc.query(anyString(), any(MapSqlParameterSource.class), any(RowMapper.class)))
                .thenReturn(List.of());

        var result = repository.findAllSuppliers();

        assertThat(result).isEmpty();
        verify(jdbc).query(contains("SELECT supplier_id"), any(MapSqlParameterSource.class), any(RowMapper.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void findDataFreshness_noUpdates_returnsCurrentInstant() {
        when(jdbc.query(anyString(), any(MapSqlParameterSource.class), any(RowMapper.class)))
                .thenReturn(List.of());

        var result = repository.findDataFreshness();

        assertThat(result).isNotNull();
    }
}
