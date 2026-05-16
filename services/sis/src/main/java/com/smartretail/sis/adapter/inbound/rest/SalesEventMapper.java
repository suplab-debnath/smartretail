package com.smartretail.sis.adapter.inbound.rest;

import com.smartretail.sis.adapter.in.web.generated.model.Channel;
import com.smartretail.sis.adapter.in.web.generated.model.SalesEventRequest;
import com.smartretail.sis.domain.model.SalesTransaction;

import java.math.BigDecimal;

final class SalesEventMapper {

    private SalesEventMapper() {}

    static SalesTransaction toDomain(SalesEventRequest request) {
        return new SalesTransaction(
                request.getTransactionId(),
                request.getStoreId(),
                request.getSkuId(),
                request.getDcId(),
                request.getQuantity(),
                BigDecimal.valueOf(request.getUnitPrice()),
                mapChannel(request.getChannel()),
                request.getEventTimestamp().toInstant()
        );
    }

    private static SalesTransaction.Channel mapChannel(Channel channel) {
        return switch (channel) {
            case POS -> SalesTransaction.Channel.POS;
            case ECOMMERCE -> SalesTransaction.Channel.ECOMMERCE;
        };
    }
}
