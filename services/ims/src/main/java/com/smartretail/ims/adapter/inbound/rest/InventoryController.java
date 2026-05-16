package com.smartretail.ims.adapter.inbound.rest;

import com.smartretail.ims.adapter.in.web.generated.api.InventoryPositionsApi;
import com.smartretail.ims.adapter.in.web.generated.api.StockAlertsApi;
import com.smartretail.ims.adapter.in.web.generated.model.AlertSeverity;
import com.smartretail.ims.adapter.in.web.generated.model.AlertStatus;
import com.smartretail.ims.adapter.in.web.generated.model.InventoryPositionPage;
import com.smartretail.ims.adapter.in.web.generated.model.StockAlertPage;
import com.smartretail.ims.port.outbound.InventoryRepositoryPort;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@Tag(name = "inventory", description = "Inventory positions and stock alerts")
public class InventoryController implements InventoryPositionsApi, StockAlertsApi {

    private final InventoryRepositoryPort inventoryRepo;
    private final InventoryResponseMapper mapper;

    public InventoryController(InventoryRepositoryPort inventoryRepo,
                               InventoryResponseMapper mapper) {
        this.inventoryRepo = inventoryRepo;
        this.mapper = mapper;
    }

    @Override
    public ResponseEntity<InventoryPositionPage> listInventoryPositions(
            String dcId, String skuId, Integer page, Integer size) {

        var positions = inventoryRepo.findPositions(dcId, skuId, page, size);
        long total = inventoryRepo.countPositions(dcId, skuId);

        InventoryPositionPage response = new InventoryPositionPage();
        response.setPositions(positions.stream().map(mapper::toApiModel).toList());
        response.setPage(page);
        response.setSize(size);
        response.setTotalElements(total);
        return ResponseEntity.ok(response);
    }

    @Override
    public ResponseEntity<StockAlertPage> listStockAlerts(
            String dcId, AlertSeverity severity, AlertStatus status, Integer page, Integer size) {

        String severityName = severity != null ? severity.getValue() : null;
        String statusName   = status   != null ? status.getValue()   : "ACTIVE";

        var alerts = inventoryRepo.findAlerts(dcId, severityName, statusName, page, size);
        long total = inventoryRepo.countAlerts(dcId, severityName, statusName);

        StockAlertPage response = new StockAlertPage();
        response.setAlerts(alerts.stream().map(mapper::toApiModel).toList());
        response.setPage(page);
        response.setSize(size);
        response.setTotalElements(total);
        return ResponseEntity.ok(response);
    }
}
