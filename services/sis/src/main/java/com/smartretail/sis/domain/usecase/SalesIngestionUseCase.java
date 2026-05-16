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

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

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
        String eventId = sha256Hex(transaction.transactionId().toString());

        if (idempotency.isDuplicate(eventId)) {
            log.warn("Duplicate event skipped: transactionId={}", transaction.transactionId());
            return new IngestionResult.Duplicate(transaction.transactionId());
        }

        String s3Uri = rawArchive.archive(transaction);
        eventStore.save(transaction, s3Uri);
        idempotency.markProcessed(eventId);
        eventPublisher.publishSalesTransactionEvent(transaction);

        log.info("SalesTransactionEvent processed: transactionId={} skuId={} dcId={}",
                transaction.transactionId(), transaction.skuId(), transaction.dcId());

        return new IngestionResult.Accepted(transaction.transactionId());
    }

    private static String sha256Hex(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
