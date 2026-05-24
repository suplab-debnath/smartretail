package com.smartretail.sis.domain.model;

import java.util.UUID;

public sealed interface IngestionResult
        permits IngestionResult.Accepted, IngestionResult.Duplicate {

    record Accepted(UUID transactionId) implements IngestionResult {}

    record Duplicate(UUID transactionId) implements IngestionResult {}
}
