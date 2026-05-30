package com.smartretail.sis.adapter.outbound.idempotency;

import com.smartretail.sis.port.outbound.IdempotencyPort;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcOperations;
import org.springframework.stereotype.Repository;

@Repository
public class RdsIdempotencyAdapter implements IdempotencyPort {

    private static final String IS_DUPLICATE_SQL =
            "SELECT 1 FROM sales.idempotency_keys WHERE event_id = :eventId";

    private static final String MARK_PROCESSED_SQL =
            "INSERT INTO sales.idempotency_keys (event_id) VALUES (:eventId) ON CONFLICT DO NOTHING";

    private final NamedParameterJdbcOperations jdbc;

    public RdsIdempotencyAdapter(NamedParameterJdbcOperations jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean isDuplicate(String eventId) {
        var params = new MapSqlParameterSource("eventId", eventId);
        return !jdbc.queryForList(IS_DUPLICATE_SQL, params, Integer.class).isEmpty();
    }

    @Override
    public void markProcessed(String eventId) {
        var params = new MapSqlParameterSource("eventId", eventId);
        jdbc.update(MARK_PROCESSED_SQL, params);
    }
}
