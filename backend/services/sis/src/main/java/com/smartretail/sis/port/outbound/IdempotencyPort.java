package com.smartretail.sis.port.outbound;

/** Outbound port: guards against duplicate event processing. */
public interface IdempotencyPort {
    /**
     * Atomically marks the event as processed.
     *
     * @return {@code true} if this is the first time the event is seen (proceed);
     *         {@code false} if it was already processed (duplicate, skip).
     */
    boolean tryMarkProcessed(String eventId);
}
