package com.smartretail.sis.adapter.inbound.rest;

import com.smartretail.sis.domain.model.SalesTransaction;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests the {@code toChannel} default method on {@link SalesEventMapper}.
 * The {@code toDomain} mapping is exercised via Spring context integration tests;
 * this class focuses on the channel switch branches not covered by controller tests.
 */
class SalesEventMapperTest {

    // Anonymous impl of the functional interface so we can call the default method.
    private final SalesEventMapper mapper = req -> null;

    @Test
    void toChannel_pos_mapsToPosChannel() {
        SalesTransaction.Channel result = mapper.toChannel(
                com.smartretail.sis.adapter.in.web.generated.model.Channel.POS);
        assertThat(result).isEqualTo(SalesTransaction.Channel.POS);
    }

    @Test
    void toChannel_ecommerce_mapsToEcommerceChannel() {
        SalesTransaction.Channel result = mapper.toChannel(
                com.smartretail.sis.adapter.in.web.generated.model.Channel.ECOMMERCE);
        assertThat(result).isEqualTo(SalesTransaction.Channel.ECOMMERCE);
    }
}
