package com.smartretail.sis.port.outbound;

/** Outbound port: guards against duplicate event processing. */
public interface IdempotencyPort {
    boolean isDuplicate(String eventId);
    void markProcessed(String eventId);
}
