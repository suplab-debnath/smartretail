package com.smartretail.sis.adapter.outbound.idempotency;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcOperations;

import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RdsIdempotencyAdapterTest {

    @Mock
    private NamedParameterJdbcOperations jdbc;

    private RdsIdempotencyAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new RdsIdempotencyAdapter(jdbc);
    }

    @Test
    void isDuplicate_returnsFalse_whenNoRowFound() {
        when(jdbc.queryForList(anyString(), org.mockito.ArgumentMatchers.<MapSqlParameterSource>any(), eq(Integer.class)))
                .thenReturn(Collections.emptyList());

        boolean result = adapter.isDuplicate("abc123");

        assertThat(result).isFalse();
    }

    @Test
    void isDuplicate_returnsTrue_whenRowExists() {
        when(jdbc.queryForList(anyString(), org.mockito.ArgumentMatchers.<MapSqlParameterSource>any(), eq(Integer.class)))
                .thenReturn(List.of(1));

        boolean result = adapter.isDuplicate("abc123");

        assertThat(result).isTrue();
    }

    @Test
    void isDuplicate_passesCorrectEventId() {
        when(jdbc.queryForList(anyString(), org.mockito.ArgumentMatchers.<MapSqlParameterSource>any(), eq(Integer.class)))
                .thenReturn(Collections.emptyList());
        ArgumentCaptor<MapSqlParameterSource> captor = ArgumentCaptor.forClass(MapSqlParameterSource.class);

        adapter.isDuplicate("sha256hex");

        verify(jdbc).queryForList(anyString(), captor.capture(), eq(Integer.class));
        assertThat(captor.getValue().getValue("eventId")).isEqualTo("sha256hex");
    }

    @Test
    void markProcessed_executesInsertWithCorrectEventId() {
        ArgumentCaptor<MapSqlParameterSource> captor = ArgumentCaptor.forClass(MapSqlParameterSource.class);

        adapter.markProcessed("sha256hex");

        verify(jdbc).update(anyString(), captor.capture());
        assertThat(captor.getValue().getValue("eventId")).isEqualTo("sha256hex");
    }
}
