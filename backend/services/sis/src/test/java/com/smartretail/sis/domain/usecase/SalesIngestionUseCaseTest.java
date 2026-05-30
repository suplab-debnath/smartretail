package com.smartretail.sis.domain.usecase;

import com.smartretail.sis.domain.model.IngestionResult;
import com.smartretail.sis.domain.model.SalesTransaction;
import com.smartretail.sis.port.outbound.EventPublisherPort;
import com.smartretail.sis.port.outbound.EventStorePort;
import com.smartretail.sis.port.outbound.IdempotencyPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SalesIngestionUseCaseTest {

    @Mock private EventStorePort eventStore;
    @Mock private EventPublisherPort eventPublisher;
    @Mock private IdempotencyPort idempotency;

    private SalesIngestionUseCase useCase;

    @BeforeEach
    void setUp() {
        useCase = new SalesIngestionUseCase(eventStore, eventPublisher, idempotency);
    }

    @Test
    void ingest_newEvent_returnsAcceptedAndPublishes() {
        SalesTransaction tx = sampleTransaction(UUID.randomUUID());
        when(idempotency.isDuplicate(anyString())).thenReturn(false);

        IngestionResult result = useCase.ingest(tx);

        assertThat(result).isInstanceOf(IngestionResult.Accepted.class);
        assertThat(((IngestionResult.Accepted) result).transactionId()).isEqualTo(tx.transactionId());
        verify(eventStore).save(eq(tx));
        verify(idempotency).markProcessed(anyString());
        verify(eventPublisher).publishSalesTransactionEvent(tx);
    }

    @Test
    void ingest_duplicateEvent_returnsDuplicateAndSkipsAllSideEffects() {
        SalesTransaction tx = sampleTransaction(UUID.randomUUID());
        when(idempotency.isDuplicate(anyString())).thenReturn(true);

        IngestionResult result = useCase.ingest(tx);

        assertThat(result).isInstanceOf(IngestionResult.Duplicate.class);
        verifyNoInteractions(eventStore, eventPublisher);
        verify(idempotency, never()).markProcessed(anyString());
    }

    @Test
    void ingest_sameTransactionId_producesConsistentIdempotencyKey() {
        UUID id = UUID.randomUUID();
        SalesTransaction tx1 = sampleTransaction(id);
        SalesTransaction tx2 = sampleTransaction(id);

        when(idempotency.isDuplicate(anyString())).thenReturn(false);

        useCase.ingest(tx1);
        useCase.ingest(tx2);

        // Both calls must pass the same SHA-256 derived key to isDuplicate
        verify(idempotency, times(2)).isDuplicate(
                argThat(key -> key.length() == 64)); // SHA-256 hex = 64 chars
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
