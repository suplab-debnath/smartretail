package com.smartretail.ars.adapter.inbound.rest;

import com.smartretail.ars.adapter.in.web.generated.model.ErrorResponse;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();
    private final MockHttpServletRequest request =
            new MockHttpServletRequest("GET", "/v1/dashboard/store-manager");

    @Test
    void handleDataAccess_returns500WithMostSpecificCause() {
        DataAccessException ex =
                new DataIntegrityViolationException("wrapper", new SQLException("duplicate key value"));

        ResponseEntity<ErrorResponse> response = handler.handleDataAccess(ex, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        ErrorResponse body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.getErrorCode()).isEqualTo(ErrorResponse.ErrorCodeEnum.INTERNAL_ERROR);
        // short cause → truncate returns it unchanged (length <= 500 branch)
        assertThat(body.getDetails()).containsEntry("cause", "duplicate key value");
        assertThat(body.getDetails()).containsEntry("exceptionType", "DataIntegrityViolationException");
    }

    @Test
    void handleUnexpected_withNullMessage_rendersNullDetail() {
        // RuntimeException with no message → truncate(null) hits the null branch
        ResponseEntity<ErrorResponse> response = handler.handleUnexpected(new RuntimeException(), request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        ErrorResponse body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.getErrorCode()).isEqualTo(ErrorResponse.ErrorCodeEnum.INTERNAL_ERROR);
        assertThat(body.getDetails()).containsEntry("detail", "null");
    }

    @Test
    void handleUnexpected_withLongMessage_truncatesTo500CharsPlusEllipsis() {
        String longMessage = "x".repeat(600);

        ResponseEntity<ErrorResponse> response =
                handler.handleUnexpected(new RuntimeException(longMessage), request);

        String detail = response.getBody().getDetails().get("detail");
        // length > 500 branch: first 500 chars + the ellipsis character
        assertThat(detail).hasSize(501);
        assertThat(detail).endsWith("…");
    }
}
