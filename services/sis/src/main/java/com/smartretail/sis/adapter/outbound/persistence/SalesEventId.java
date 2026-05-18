package com.smartretail.sis.adapter.outbound.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

import java.io.Serializable;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

@Embeddable
public class SalesEventId implements Serializable {

    @Column(name = "transaction_id", nullable = false)
    private UUID transactionId;

    @Column(name = "event_date", nullable = false)
    private LocalDate eventDate;

    protected SalesEventId() {
    }

    public SalesEventId(UUID transactionId, LocalDate eventDate) {
        this.transactionId = transactionId;
        this.eventDate = eventDate;
    }

    public UUID transactionId() {
        return transactionId;
    }

    public LocalDate eventDate() {
        return eventDate;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o)
            return true;
        if (!(o instanceof SalesEventId that))
            return false;
        return Objects.equals(transactionId, that.transactionId)
                && Objects.equals(eventDate, that.eventDate);
    }

    @Override
    public int hashCode() {
        return Objects.hash(transactionId, eventDate);
    }
}
