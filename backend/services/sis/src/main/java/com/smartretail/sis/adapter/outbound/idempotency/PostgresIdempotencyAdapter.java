package com.smartretail.sis.adapter.outbound.idempotency;

import com.smartretail.sis.port.outbound.IdempotencyPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class PostgresIdempotencyAdapter implements IdempotencyPort {

    private static final String INSERT_SQL =
            "INSERT INTO sales.processed_transactions (transaction_id) VALUES (?) ON CONFLICT DO NOTHING";

    private final JdbcTemplate jdbcTemplate;

    public PostgresIdempotencyAdapter(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public boolean tryMarkProcessed(String eventId) {
        int updated = jdbcTemplate.update(INSERT_SQL, java.util.UUID.fromString(eventId));
        return updated == 1;
    }
}
