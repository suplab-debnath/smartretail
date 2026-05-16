package com.smartretail.ims.domain.usecase;

import com.smartretail.ims.domain.model.AlertSeverity;
import com.smartretail.ims.domain.model.AlertType;
import com.smartretail.ims.domain.model.InventoryPosition;
import com.smartretail.ims.domain.model.SalesTransactionEventDto;
import com.smartretail.ims.domain.model.StockAlert;
import com.smartretail.ims.domain.model.exception.InventoryPositionNotFoundException;
import com.smartretail.ims.domain.model.exception.OptimisticLockException;
import com.smartretail.ims.port.inbound.InventoryUpdatePort;
import com.smartretail.ims.port.outbound.AlertPublisherPort;
import com.smartretail.ims.port.outbound.InventoryRepositoryPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class InventoryUpdateUseCase implements InventoryUpdatePort {

    private static final Logger log = LoggerFactory.getLogger(InventoryUpdateUseCase.class);
    private static final int MAX_RETRIES = 3;

    private final InventoryRepositoryPort inventoryRepo;
    private final AlertPublisherPort alertPublisher;

    public InventoryUpdateUseCase(InventoryRepositoryPort inventoryRepo,
                                  AlertPublisherPort alertPublisher) {
        this.inventoryRepo = inventoryRepo;
        this.alertPublisher = alertPublisher;
    }

    @Override
    @Transactional
    public void processSalesEvent(SalesTransactionEventDto event) {
        log.info("SQS message received: transactionId={} skuId={} dcId={} qty={}",
                event.transactionId(), event.skuId(), event.dcId(), event.quantity());

        InventoryPosition position = inventoryRepo
                .findBySkuAndDc(event.skuId(), event.dcId())
                .orElseThrow(() -> new InventoryPositionNotFoundException(event.skuId(), event.dcId()));

        decrementWithRetry(position, event.quantity());

        // Reload after successful decrement to get accurate ATP
        position = inventoryRepo.findById(position.getPositionId()).orElseThrow();

        if (position.isLowStock()) {
            AlertSeverity severity = position.computeSeverity();
            StockAlert alert = StockAlert.create(position, AlertType.LOW_STOCK, severity);
            inventoryRepo.saveAlert(alert);
            alertPublisher.publishInventoryAlertEvent(alert, position);
            log.info("InventoryAlertEvent published: alertId={} skuId={} severity={}",
                    alert.getAlertId(), position.getSkuId(), severity);
        }
    }

    private void decrementWithRetry(InventoryPosition position, int quantity) {
        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            int updated = inventoryRepo.decrementOnHand(
                    position.getPositionId(), quantity, position.getVersion());

            if (updated > 0) return;

            if (attempt == MAX_RETRIES) {
                throw new OptimisticLockException(
                        "Concurrent modification on positionId=" + position.getPositionId()
                        + " after " + MAX_RETRIES + " attempts");
            }

            // Reload with fresh version for next attempt
            position = inventoryRepo.findById(position.getPositionId()).orElseThrow();
        }
    }
}
