package com.smartretail.sis.adapter.outbound.idempotency;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcOperations;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collections;

@Component
public class IdempotencyCleanupJob {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyCleanupJob.class);

    private static final String DELETE_SQL =
            "DELETE FROM sales.idempotency_keys WHERE received_at < NOW() - INTERVAL '48 hours'";

    private final NamedParameterJdbcOperations jdbc;

    public IdempotencyCleanupJob(NamedParameterJdbcOperations jdbc) {
        this.jdbc = jdbc;
    }

    @Scheduled(fixedDelayString = "PT1H")
    @Transactional
    public void purgeExpiredKeys() {
        int deleted = jdbc.update(DELETE_SQL, Collections.emptyMap());
        log.info("Idempotency cleanup: removed {} expired keys", deleted);
    }
}
