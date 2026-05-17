package com.smartretail.sis.domain.model.exception;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class DuplicateEventExceptionTest {

    @Test
    void constructor_setsTransactionIdAndMessage() {
        UUID txId = UUID.fromString("11111111-1111-1111-1111-111111111111");
        DuplicateEventException ex = new DuplicateEventException(txId);
        assertThat(ex.getTransactionId()).isEqualTo(txId);
        assertThat(ex.getMessage()).contains(txId.toString());
    }

    @Test
    void isRuntimeException() {
        assertThat(new DuplicateEventException(UUID.randomUUID()))
                .isInstanceOf(RuntimeException.class);
    }
}
