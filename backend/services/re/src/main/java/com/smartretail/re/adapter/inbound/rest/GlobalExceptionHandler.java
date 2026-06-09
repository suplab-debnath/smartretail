package com.smartretail.re.adapter.inbound.rest;

import com.smartretail.re.adapter.inbound.rest.generated.model.ErrorResponse;
import com.smartretail.re.domain.model.exception.InvalidStatusTransitionException;
import com.smartretail.re.domain.model.exception.OptimisticLockException;
import com.smartretail.re.domain.model.exception.PurchaseOrderNotFoundException;
import com.smartretail.re.domain.model.exception.ReplenishmentRuleNotFoundException;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(PurchaseOrderNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleNotFound(PurchaseOrderNotFoundException ex) {
        return buildResponse(HttpStatus.NOT_FOUND, ErrorResponse.ErrorCodeEnum.NOT_FOUND, ex.getMessage(), null);
    }

    @ExceptionHandler(ReplenishmentRuleNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleRuleNotFound(ReplenishmentRuleNotFoundException ex) {
        return buildResponse(HttpStatus.NOT_FOUND, ErrorResponse.ErrorCodeEnum.NOT_FOUND, ex.getMessage(), null);
    }

    @ExceptionHandler(InvalidStatusTransitionException.class)
    public ResponseEntity<ErrorResponse> handleInvalidTransition(InvalidStatusTransitionException ex) {
        log.warn("Invalid status transition: {}", ex.getMessage());
        return buildResponse(HttpStatus.CONFLICT, ErrorResponse.ErrorCodeEnum.INVALID_STATUS_TRANSITION,
                ex.getMessage(), Map.of("currentStatus", ex.getCurrentStatus()));
    }

    @ExceptionHandler(OptimisticLockException.class)
    public ResponseEntity<ErrorResponse> handleOptimisticLock(OptimisticLockException ex) {
        return buildResponse(HttpStatus.CONFLICT, ErrorResponse.ErrorCodeEnum.CONCURRENT_MODIFICATION, ex.getMessage(),
                null);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException ex) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
                .findFirst()
                .orElse("Validation failed");
        return buildResponse(HttpStatus.BAD_REQUEST, ErrorResponse.ErrorCodeEnum.VALIDATION_ERROR, message, null);
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ErrorResponse> handleAccessDenied(AccessDeniedException ex) {
        return buildResponse(HttpStatus.FORBIDDEN, ErrorResponse.ErrorCodeEnum.UNAUTHORIZED, ex.getMessage(), null);
    }

    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<ErrorResponse> handleDataAccess(DataAccessException ex, HttpServletRequest req) {
        String cause = ex.getMostSpecificCause().getMessage();
        log.error("Data access error on {} [{}]: {}", req.getRequestURI(),
                ex.getClass().getSimpleName(), cause, ex);
        return buildResponse(HttpStatus.INTERNAL_SERVER_ERROR, ErrorResponse.ErrorCodeEnum.INTERNAL_ERROR,
                "Database error — see traceId in logs",
                Map.of("exceptionType", ex.getClass().getSimpleName(), "cause", truncate(cause)));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleGeneral(Exception ex, HttpServletRequest req) {
        log.error("Unexpected error on {} [{}]: {}",
                req.getRequestURI(), ex.getClass().getSimpleName(), ex.getMessage(), ex);
        return buildResponse(HttpStatus.INTERNAL_SERVER_ERROR, ErrorResponse.ErrorCodeEnum.INTERNAL_ERROR,
                "An unexpected error occurred",
                Map.of("exceptionType", ex.getClass().getSimpleName(), "detail", truncate(ex.getMessage())));
    }

    private ResponseEntity<ErrorResponse> buildResponse(HttpStatus status,
            ErrorResponse.ErrorCodeEnum errorCode,
            String message,
            Map<String, String> details) {
        ErrorResponse response = new ErrorResponse();
        response.setErrorCode(errorCode);
        response.setMessage(message);
        response.setTimestamp(OffsetDateTime.now(ZoneOffset.UTC));
        response.setTraceId(MDC.get("traceId"));
        if (details != null) {
            response.setDetails(details);
        }
        return ResponseEntity.status(status).body(response);
    }

    private static String truncate(String s) {
        if (s == null)
            return "null";
        return s.length() > 500 ? s.substring(0, 500) + "…" : s;
    }
}
