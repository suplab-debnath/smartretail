package com.smartretail.sis.domain.usecase;

import com.smartretail.sis.domain.model.IngestionResult;
import com.smartretail.sis.domain.model.SalesTransaction;
import com.smartretail.sis.port.outbound.EventPublisherPort;
import com.smartretail.sis.port.outbound.EventStorePort;
import com.smartretail.sis.port.outbound.IdempotencyPort;
import com.smartretail.sis.port.outbound.RawArchivePort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SalesIngestionUseCaseTest {

    @Mock private EventStorePort eventStore;
    @Mock private EventPublisherPort eventPublisher;
    @Mock private RawArchivePort rawArchive;
    @Mock private IdempotencyPort idempotency;

    private SalesIngestionUseCase useCase;

    @BeforeEach
    void setUp() {
        useCase = new SalesIngestionUseCase(eventStore, eventPublisher, rawArchive, idempotency);
    }

    @Test
    void ingest_newEvent_returnsAcceptedAndPublishes() {
        SalesTransaction tx = sampleTransaction(UUID.randomUUID());
        when(idempotency.tryMarkProcessed(anyString())).thenReturn(true);
        when(rawArchive.archive(tx)).thenReturn("firehose://smartretail-pos-archive-dev/" + tx.transactionId());

        IngestionResult result = useCase.ingest(tx);

        assertThat(result).isInstanceOf(IngestionResult.Accepted.class);
        assertThat(((IngestionResult.Accepted) result).transactionId()).isEqualTo(tx.transactionId());
        verify(eventStore).save(eq(tx), anyString());
        verify(eventPublisher).publishSalesTransactionEvent(tx);
    }

    @Test
    void ingest_duplicateEvent_returnsDuplicateAndSkipsAllSideEffects() {
        SalesTransaction tx = sampleTransaction(UUID.randomUUID());
        when(idempotency.tryMarkProcessed(anyString())).thenReturn(false);

        IngestionResult result = useCase.ingest(tx);

        assertThat(result).isInstanceOf(IngestionResult.Duplicate.class);
        verifyNoInteractions(eventStore, rawArchive, eventPublisher);
    }

    @Test
    void ingest_sameTransactionId_producesConsistentIdempotencyKey() {
        UUID id = UUID.randomUUID();
        SalesTransaction tx1 = sampleTransaction(id);
        SalesTransaction tx2 = sampleTransaction(id);

        when(idempotency.tryMarkProcessed(anyString())).thenReturn(true);
        when(rawArchive.archive(any())).thenReturn("firehose://stream/key");

        useCase.ingest(tx1);
        useCase.ingest(tx2);

        // Both calls must pass the same transactionId string to tryMarkProcessed
        verify(idempotency, times(2)).tryMarkProcessed(id.toString());
    }

    private SalesTransaction sampleTransaction(UUID id) {
        return new SalesTransaction(
                id,
                "STORE-001",
                "SKU-BEV-001",
                "DC-LONDON",
                30,
                BigDecimal.valueOf(8.50),
                SalesTransaction.Channel.POS,
                Instant.parse("2026-05-15T14:23:00Z")
        );
    }
}
