package com.smartretail.sis.domain.usecase;

import com.smartretail.sis.domain.model.IngestionResult;
import com.smartretail.sis.domain.model.SalesTransaction;
import com.smartretail.sis.port.inbound.SalesEventPort;
import com.smartretail.sis.port.outbound.EventPublisherPort;
import com.smartretail.sis.port.outbound.EventStorePort;
import com.smartretail.sis.port.outbound.IdempotencyPort;
import com.smartretail.sis.port.outbound.RawArchivePort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;


@Service
public class SalesIngestionUseCase implements SalesEventPort {

    private static final Logger log = LoggerFactory.getLogger(SalesIngestionUseCase.class);

    private final EventStorePort eventStore;
    private final EventPublisherPort eventPublisher;
    private final RawArchivePort rawArchive;
    private final IdempotencyPort idempotency;

    public SalesIngestionUseCase(
            EventStorePort eventStore,
            EventPublisherPort eventPublisher,
            RawArchivePort rawArchive,
            IdempotencyPort idempotency) {
        this.eventStore = eventStore;
        this.eventPublisher = eventPublisher;
        this.rawArchive = rawArchive;
        this.idempotency = idempotency;
    }

    @Override
    @Transactional
    public IngestionResult ingest(SalesTransaction transaction) {
        String transactionId = transaction.transactionId().toString();

        if (!idempotency.tryMarkProcessed(transactionId)) {
            log.warn("Duplicate event skipped: transactionId={}", transaction.transactionId());
            return new IngestionResult.Duplicate(transaction.transactionId());
        }

        String archiveRef = rawArchive.archive(transaction);
        eventStore.save(transaction, archiveRef);
        eventPublisher.publishSalesTransactionEvent(transaction);

        log.info("SalesTransactionEvent processed: transactionId={} skuId={} dcId={}",
                transaction.transactionId(), transaction.skuId(), transaction.dcId());

        return new IngestionResult.Accepted(transaction.transactionId());
    }
}
