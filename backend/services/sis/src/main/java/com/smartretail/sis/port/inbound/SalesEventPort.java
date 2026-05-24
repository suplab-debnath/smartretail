package com.smartretail.sis.port.inbound;

import com.smartretail.sis.domain.model.IngestionResult;
import com.smartretail.sis.domain.model.SalesTransaction;

/** Inbound port: accepts a sales transaction for ingestion. */
public interface SalesEventPort {
    IngestionResult ingest(SalesTransaction transaction);
}
