package com.smartretail.dfs.adapter.inbound.rest;

import com.smartretail.dfs.adapter.in.web.generated.model.ErrorResponse;
import com.smartretail.dfs.domain.model.exception.ForecastRunNotFoundException;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ForecastRunNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleRunNotFound(ForecastRunNotFoundException ex) {
        ErrorResponse response = new ErrorResponse(
                ErrorResponse.ErrorCodeEnum.NOT_FOUND,
                ex.getMessage(),
                OffsetDateTime.now(ZoneOffset.UTC)
        );
        return ResponseEntity.status(404).body(response);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(Exception ex, HttpServletRequest req) {
        log.error("Unexpected error processing {}", req.getRequestURI(), ex);
        ErrorResponse response = new ErrorResponse(
                ErrorResponse.ErrorCodeEnum.INTERNAL_ERROR,
                "An unexpected error occurred",
                OffsetDateTime.now(ZoneOffset.UTC)
        );
        return ResponseEntity.status(500).body(response);
    }
}
