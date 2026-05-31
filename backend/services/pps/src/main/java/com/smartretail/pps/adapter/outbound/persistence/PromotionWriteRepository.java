package com.smartretail.pps.adapter.outbound.persistence;

import com.smartretail.pps.domain.model.PromotionActivationCommand;
import com.smartretail.pps.port.outbound.PromotionWritePort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcOperations;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.UUID;

/**
 * Upserts promotion schedules received from Campaign Management via EventBridge.
 * ON CONFLICT ensures idempotent handling of duplicate event deliveries.
 */
@Repository
public class PromotionWriteRepository implements PromotionWritePort {

    private static final Logger log = LoggerFactory.getLogger(PromotionWriteRepository.class);

    /**
     * The sku_ids column is UUID[] in the DB schema. We store the skuIds as-is via
     * PostgreSQL's cast. The SQL uses ARRAY[…]::text[]::uuid[] when values are UUIDs,
     * but for prototype simplicity we omit dc_ids and set uplift_factor default 1.0.
     * source_event_id is set to the promotionId (the external event identifier).
     */
    private static final String UPSERT_SQL = """
            INSERT INTO promotions.promotion_schedules (
                promotion_id,
                promotion_name,
                sku_ids,
                discount_pct,
                uplift_factor,
                elasticity_coeff,
                valid_from,
                valid_to,
                status,
                source_event_id
            ) VALUES (
                :promotionId::uuid,
                :promotionName,
                CAST(:skuIds AS uuid[]),
                :discountPct,
                1.0,
                1.0,
                :validFrom,
                :validTo,
                'ACTIVE',
                :promotionId::uuid
            )
            ON CONFLICT (promotion_id) DO UPDATE SET
                promotion_name   = EXCLUDED.promotion_name,
                sku_ids          = EXCLUDED.sku_ids,
                discount_pct     = EXCLUDED.discount_pct,
                valid_from       = EXCLUDED.valid_from,
                valid_to         = EXCLUDED.valid_to,
                status           = EXCLUDED.status
            """;

    private final NamedParameterJdbcOperations jdbc;

    public PromotionWriteRepository(NamedParameterJdbcOperations jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void upsert(PromotionActivationCommand command) {
        // Convert List<String> skuIds to a PostgreSQL UUID array via text representation
        String skuIdsArray = toPostgresUuidArray(command.skuIds().toArray(String[]::new));

        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("promotionId",   command.promotionId().toString())
                .addValue("promotionName", command.promotionName())
                .addValue("skuIds",        skuIdsArray)
                .addValue("discountPct",   command.discountPct())
                .addValue("validFrom",     LocalDateTime.of(command.validFrom(), LocalTime.MIDNIGHT))
                .addValue("validTo",       LocalDateTime.of(command.validTo(), LocalTime.MIDNIGHT));

        int rows = jdbc.update(UPSERT_SQL, params);
        log.debug("Upserted promotion promotionId={} rows={}", command.promotionId(), rows);
    }

    /**
     * Builds a PostgreSQL array literal string for UUID values:
     * e.g. {"550e8400-e29b-41d4-a716-446655440000","..."}
     * When inserted with ::uuid[], PostgreSQL validates each element.
     */
    static String toPostgresUuidArray(String[] values) {
        if (values == null || values.length == 0) {
            return "{}";
        }
        StringBuilder sb = new StringBuilder("{");
        for (int i = 0; i < values.length; i++) {
            if (i > 0) sb.append(',');
            sb.append('"').append(values[i].replace("\"", "\\\"")).append('"');
        }
        sb.append('}');
        return sb.toString();
    }
}
