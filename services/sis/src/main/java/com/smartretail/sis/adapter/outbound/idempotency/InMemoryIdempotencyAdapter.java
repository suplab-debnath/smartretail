package com.smartretail.sis.adapter.outbound.idempotency;

import com.smartretail.sis.port.outbound.IdempotencyPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

@Component
@Profile("local")
public class InMemoryIdempotencyAdapter implements IdempotencyPort {

    private static final Logger log = LoggerFactory.getLogger(InMemoryIdempotencyAdapter.class);
    private static final long TTL_SECONDS = 48 * 60 * 60;

    private final ConcurrentHashMap<String, Instant> store = new ConcurrentHashMap<>();

    @Override
    public boolean isDuplicate(String eventId) {
        Instant expiry = store.get(eventId);
        if (expiry == null) {
            return false;
        }
        if (Instant.now().isAfter(expiry)) {
            store.remove(eventId);
            return false;
        }
        return true;
    }

    @Override
    public void markProcessed(String eventId) {
        store.put(eventId, Instant.now().plusSeconds(TTL_SECONDS));
        log.debug("Marked event as processed: {}", eventId);
    }
}
