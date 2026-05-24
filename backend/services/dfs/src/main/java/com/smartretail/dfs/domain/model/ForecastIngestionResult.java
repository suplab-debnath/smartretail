package com.smartretail.dfs.domain.model;

import java.time.Instant;
import java.util.UUID;

/**
 * Result returned by ForecastWritePort after a successful ingestion.
 * No AWS imports.
 */
public record ForecastIngestionResult(
        UUID runId,
        int rowsInserted,
        Instant ingestedAt) {}
