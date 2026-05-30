package com.smartretail.sis.adapter.outbound.idempotency;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcOperations;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class IdempotencyCleanupJobTest {

    @Mock
    private NamedParameterJdbcOperations jdbc;

    private IdempotencyCleanupJob job;

    @BeforeEach
    void setUp() {
        job = new IdempotencyCleanupJob(jdbc);
    }

    @Test
    void purgeExpiredKeys_executesDeleteSql() {
        when(jdbc.update(anyString(), anyMap())).thenReturn(5);

        job.purgeExpiredKeys();

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbc).update(sqlCaptor.capture(), anyMap());
        String sql = sqlCaptor.getValue();
        assertThat(sql).contains("DELETE FROM sales.idempotency_keys");
        assertThat(sql).contains("48 hours");
    }

    @Test
    void purgeExpiredKeys_passesEmptyParamMap() {
        when(jdbc.update(anyString(), anyMap())).thenReturn(0);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> mapCaptor = ArgumentCaptor.forClass(Map.class);

        job.purgeExpiredKeys();

        verify(jdbc).update(anyString(), mapCaptor.capture());
        assertThat(mapCaptor.getValue()).isEmpty();
    }
}
