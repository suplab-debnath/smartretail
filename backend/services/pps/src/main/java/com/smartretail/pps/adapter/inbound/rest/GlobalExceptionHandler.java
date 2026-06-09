package com.smartretail.pps.adapter.inbound.rest;

import com.smartretail.pps.adapter.in.web.generated.model.ErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<ErrorResponse> handleDataAccess(DataAccessException ex,
            HttpServletRequest req) {
        String cause = ex.getMostSpecificCause().getMessage();
        log.error("Data access error on {} [{}]: {}", req.getRequestURI(),
                ex.getClass().getSimpleName(), cause, ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(buildError(ErrorResponse.ErrorCodeEnum.INTERNAL_ERROR,
                        "Database error — see traceId in logs",
                        Map.of(
                                "exceptionType", ex.getClass().getSimpleName(),
                                "cause", truncate(cause))));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(Exception ex, HttpServletRequest req) {
        log.error("Unexpected error on {} [{}]: {}",
                req.getRequestURI(), ex.getClass().getSimpleName(), ex.getMessage(), ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(buildError(ErrorResponse.ErrorCodeEnum.INTERNAL_ERROR,
                        "An unexpected error occurred",
                        Map.of(
                                "exceptionType", ex.getClass().getSimpleName(),
                                "detail", truncate(ex.getMessage()))));
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private ErrorResponse buildError(ErrorResponse.ErrorCodeEnum code,
            String message,
            Map<String, String> details) {
        ErrorResponse response = new ErrorResponse();
        response.setErrorCode(code);
        response.setMessage(message);
        response.setTimestamp(OffsetDateTime.now(ZoneOffset.UTC));
        response.setTraceId(MDC.get("traceId"));
        if (details != null) {
            response.setDetails(details);
        }
        return response;
    }

    private static String truncate(String s) {
        if (s == null)
            return "null";
        return s.length() > 500 ? s.substring(0, 500) + "…" : s;
    }
}
