package com.smartretail.re.adapter.outbound.event;

import com.smartretail.re.domain.model.PurchaseOrder;
import com.smartretail.re.port.outbound.PurchaseOrderEventPublisherPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Local-only no-op publisher for PurchaseOrderEvents. ARS reads purchase order state
 * directly from the database, so no event delivery is required in local mode.
 */
@Component
@Profile("local")
public class MockPurchaseOrderEventPublisher implements PurchaseOrderEventPublisherPort {

    private static final Logger log = LoggerFactory.getLogger(MockPurchaseOrderEventPublisher.class);

    @Override
    public void publishPurchaseOrderEvent(PurchaseOrder po) {
        log.info("MockPurchaseOrderEventPublisher (no-op): poId={} status={} skuId={} dcId={}",
                po.getPoId(), po.getWorkflowStatus(), po.getSkuId(), po.getDcId());
    }
}
