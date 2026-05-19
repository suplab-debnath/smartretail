package com.smartretail.sis.adapter.outbound.archive;

import com.smartretail.sis.domain.model.SalesTransaction;
import com.smartretail.sis.port.outbound.RawArchivePort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

@Component
@Profile("local")
public class NoOpArchiveAdapter implements RawArchivePort {

    private static final Logger log = LoggerFactory.getLogger(NoOpArchiveAdapter.class);
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ISO_LOCAL_DATE.withZone(ZoneOffset.UTC);

    @Override
    public String archive(SalesTransaction transaction) {
        String date = DATE_FMT.format(transaction.eventTimestamp());
        String mockUri = "mock://local/events/" + date + "/" + transaction.transactionId() + ".json";
        log.info("Mock archive (no-op): transactionId={} → {}", transaction.transactionId(), mockUri);
        return mockUri;
    }
}
