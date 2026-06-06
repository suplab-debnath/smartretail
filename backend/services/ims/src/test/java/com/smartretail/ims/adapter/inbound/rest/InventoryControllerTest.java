package com.smartretail.ims.adapter.inbound.rest;

import com.smartretail.ims.domain.model.AlertSeverity;
import com.smartretail.ims.domain.model.AlertType;
import com.smartretail.ims.domain.model.InventoryPosition;
import com.smartretail.ims.domain.model.StockAlert;
import com.smartretail.ims.port.outbound.InventoryRepositoryPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mapstruct.factory.Mappers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@ExtendWith(MockitoExtension.class)
class InventoryControllerTest {

    @Mock
    private InventoryRepositoryPort inventoryRepo;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        InventoryResponseMapper mapper = Mappers.getMapper(InventoryResponseMapper.class);
        InventoryController controller = new InventoryController(inventoryRepo, mapper);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    // ── Helper factories ──────────────────────────────────────────────────────

    private InventoryPosition makePosition(String skuId, String dcId) {
        InventoryPosition pos = new InventoryPosition();
        pos.setPositionId(UUID.randomUUID());
        pos.setSkuId(skuId);
        pos.setDcId(dcId);
        pos.setOnHand(100);
        pos.setInTransit(20);
        pos.setReserved(5);
        pos.setReorderPoint(50);
        pos.setSafetyStock(20);
        pos.setVersion(1);
        pos.setLastUpdatedAt(Instant.now());
        return pos;
    }

    private StockAlert makeAlert(String skuId, String dcId) {
        InventoryPosition pos = makePosition(skuId, dcId);
        return StockAlert.fromDb(
                UUID.randomUUID(), pos.getPositionId(), skuId, dcId,
                AlertType.LOW_STOCK, AlertSeverity.HIGH,
                50, 10, "ACTIVE", Instant.now());
    }

    // ── Inventory positions ───────────────────────────────────────────────────

    @Test
    void listInventoryPositions_returnsPagedResults() throws Exception {
        InventoryPosition pos = makePosition("SKU-BEV-001", "DC-LONDON");
        when(inventoryRepo.findPositions(null, null, 0, 20)).thenReturn(List.of(pos));
        when(inventoryRepo.countPositions(null, null)).thenReturn(1L);

        mockMvc.perform(get("/v1/inventory/positions")
                        .param("page", "0")
                        .param("size", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.positions[0].skuId").value("SKU-BEV-001"))
                .andExpect(jsonPath("$.positions[0].dcId").value("DC-LONDON"))
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    void listInventoryPositions_filteredByDcId_callsRepoWithDcId() throws Exception {
        when(inventoryRepo.findPositions("DC-LONDON", null, 0, 20)).thenReturn(List.of());
        when(inventoryRepo.countPositions("DC-LONDON", null)).thenReturn(0L);

        mockMvc.perform(get("/v1/inventory/positions")
                        .param("dcId", "DC-LONDON")
                        .param("page", "0")
                        .param("size", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));

        verify(inventoryRepo).findPositions("DC-LONDON", null, 0, 20);
    }

    @Test
    void listInventoryPositions_emptyResult_returnsEmptyPage() throws Exception {
        when(inventoryRepo.findPositions(null, null, 0, 20)).thenReturn(List.of());
        when(inventoryRepo.countPositions(null, null)).thenReturn(0L);

        mockMvc.perform(get("/v1/inventory/positions")
                        .param("page", "0")
                        .param("size", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.positions").isArray())
                .andExpect(jsonPath("$.positions").isEmpty());
    }

    // ── Stock alerts ──────────────────────────────────────────────────────────

    @Test
    void listStockAlerts_returnsActiveAlerts() throws Exception {
        StockAlert alert = makeAlert("SKU-BEV-001", "DC-LONDON");
        when(inventoryRepo.findAlerts(null, null, "ACTIVE", 0, 20)).thenReturn(List.of(alert));
        when(inventoryRepo.countAlerts(null, null, "ACTIVE")).thenReturn(1L);

        mockMvc.perform(get("/v1/inventory/alerts")
                        .param("page", "0")
                        .param("size", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.alerts[0].skuId").value("SKU-BEV-001"))
                .andExpect(jsonPath("$.alerts[0].dcId").value("DC-LONDON"))
                .andExpect(jsonPath("$.alerts[0].severity").value("HIGH"))
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    void listStockAlerts_filteredBySeverity_callsRepoWithSeverity() throws Exception {
        when(inventoryRepo.findAlerts(null, "CRITICAL", "ACTIVE", 0, 20)).thenReturn(List.of());
        when(inventoryRepo.countAlerts(null, "CRITICAL", "ACTIVE")).thenReturn(0L);

        mockMvc.perform(get("/v1/inventory/alerts")
                        .param("severity", "CRITICAL")
                        .param("page", "0")
                        .param("size", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));

        verify(inventoryRepo).findAlerts(null, "CRITICAL", "ACTIVE", 0, 20);
    }

    @Test
    void listStockAlerts_filteredByDcId_callsRepoWithDcId() throws Exception {
        StockAlert alert = makeAlert("SKU-ELEC-002", "DC-PARIS");
        when(inventoryRepo.findAlerts("DC-PARIS", null, "ACTIVE", 0, 20)).thenReturn(List.of(alert));
        when(inventoryRepo.countAlerts("DC-PARIS", null, "ACTIVE")).thenReturn(1L);

        mockMvc.perform(get("/v1/inventory/alerts")
                        .param("dcId", "DC-PARIS")
                        .param("page", "0")
                        .param("size", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.alerts[0].dcId").value("DC-PARIS"));
    }

    // ── null page / size — default value branches ─────────────────────────────

    @Test
    void listInventoryPositions_withNoPageOrSize_usesDefaults() throws Exception {
        // Omitting page and size triggers the null-safe default branches (page=0, size=20)
        when(inventoryRepo.findPositions(null, null, 0, 20)).thenReturn(List.of());
        when(inventoryRepo.countPositions(null, null)).thenReturn(0L);

        mockMvc.perform(get("/v1/inventory/positions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(20));

        verify(inventoryRepo).findPositions(null, null, 0, 20);
    }

    @Test
    void listStockAlerts_withNoPageOrSize_usesDefaults() throws Exception {
        // Omitting page and size triggers the null-safe default branches (page=0, size=20)
        when(inventoryRepo.findAlerts(null, null, "ACTIVE", 0, 20)).thenReturn(List.of());
        when(inventoryRepo.countAlerts(null, null, "ACTIVE")).thenReturn(0L);

        mockMvc.perform(get("/v1/inventory/alerts"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(20));

        verify(inventoryRepo).findAlerts(null, null, "ACTIVE", 0, 20);
    }

    @Test
    void listStockAlerts_withExplicitResolvedStatus_passesStatusToRepo() throws Exception {
        // Exercises the status != null branch: status.getValue() is used instead of "ACTIVE"
        when(inventoryRepo.findAlerts(null, null, "RESOLVED", 0, 20)).thenReturn(List.of());
        when(inventoryRepo.countAlerts(null, null, "RESOLVED")).thenReturn(0L);

        mockMvc.perform(get("/v1/inventory/alerts")
                        .param("status", "RESOLVED")
                        .param("page", "0")
                        .param("size", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));

        verify(inventoryRepo).findAlerts(null, null, "RESOLVED", 0, 20);
    }
}
