package com.smartretail.sis.adapter.inbound.rest;

import com.smartretail.sis.adapter.in.web.generated.model.ErrorResponse;
import com.smartretail.sis.domain.model.exception.DuplicateEventException;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(DuplicateEventException.class)
    public ResponseEntity<ErrorResponse> handleDuplicate(DuplicateEventException ex) {
        return ResponseEntity.status(409).body(errorResponse(
                ErrorResponse.ErrorCodeEnum.DUPLICATE_EVENT,
                ex.getMessage()
        ));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException ex) {
        List<String> details = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
                .toList();
        ErrorResponse response = errorResponse(ErrorResponse.ErrorCodeEnum.VALIDATION_ERROR, "Request validation failed");
        response.setDetails(details);
        return ResponseEntity.badRequest().body(response);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(Exception ex, HttpServletRequest req) {
        log.error("Unexpected error processing {}", req.getRequestURI(), ex);
        return ResponseEntity.status(500).body(errorResponse(
                ErrorResponse.ErrorCodeEnum.INTERNAL_ERROR,
                "An unexpected error occurred"
        ));
    }

    private ErrorResponse errorResponse(ErrorResponse.ErrorCodeEnum code, String message) {
        ErrorResponse response = new ErrorResponse(code, message, OffsetDateTime.now(ZoneOffset.UTC));
        response.setTraceId(MDC.get("traceId"));
        return response;
    }
}
