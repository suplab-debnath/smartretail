package com.smartretail.sis.adapter.inbound.rest;

import com.smartretail.sis.adapter.in.web.generated.model.SalesEventRequest;
import com.smartretail.sis.domain.model.SalesTransaction;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface SalesEventMapper {

    @Mapping(target = "unitPrice",
             expression = "java(java.math.BigDecimal.valueOf(request.getUnitPrice()))")
    @Mapping(target = "eventTimestamp",
             expression = "java(request.getEventTimestamp().toInstant())")
    SalesTransaction toDomain(SalesEventRequest request);

    default SalesTransaction.Channel toChannel(
            com.smartretail.sis.adapter.in.web.generated.model.Channel channel) {
        return switch (channel) {
            case POS       -> SalesTransaction.Channel.POS;
            case ECOMMERCE -> SalesTransaction.Channel.ECOMMERCE;
        };
    }
}
