package com.smartretail.re.port.inbound;

import com.smartretail.re.domain.model.InventoryAlertEventDto;

public interface ProcessInventoryAlertPort {

    void processInventoryAlert(InventoryAlertEventDto alert);
}
