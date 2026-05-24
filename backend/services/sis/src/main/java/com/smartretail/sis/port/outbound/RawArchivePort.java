package com.smartretail.sis.port.outbound;

import com.smartretail.sis.domain.model.SalesTransaction;

/** Outbound port: archives the raw event JSON to object storage. */
public interface RawArchivePort {
    /** @return the S3 URI of the archived object */
    String archive(SalesTransaction transaction);
}
