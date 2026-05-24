package com.smartretail.re.adapter.inbound.rest;

import com.smartretail.re.adapter.inbound.rest.generated.model.ErrorResponse;
import com.smartretail.re.domain.model.exception.InvalidStatusTransitionException;
import com.smartretail.re.domain.model.exception.OptimisticLockException;
import com.smartretail.re.domain.model.exception.PurchaseOrderNotFoundException;
import com.smartretail.re.domain.model.exception.ReplenishmentRuleNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
        return buildResponse(HttpStatus.NOT_FOUND, ErrorResponse.ErrorCodeEnum.NOT_FOUND, ex.getMessage());
    }

    @ExceptionHandler(ReplenishmentRuleNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleRuleNotFound(ReplenishmentRuleNotFoundException ex) {
        return buildResponse(HttpStatus.NOT_FOUND, ErrorResponse.ErrorCodeEnum.NOT_FOUND, ex.getMessage());
    }

    @ExceptionHandler(InvalidStatusTransitionException.class)
    public ResponseEntity<ErrorResponse> handleInvalidTransition(InvalidStatusTransitionException ex) {
        ErrorResponse response = new ErrorResponse();
        response.setErrorCode(ErrorResponse.ErrorCodeEnum.INVALID_STATUS_TRANSITION);
        response.setMessage(ex.getMessage());
        response.setTimestamp(OffsetDateTime.now(ZoneOffset.UTC));
        response.setDetails(Map.of("currentStatus", ex.getCurrentStatus()));
        log.warn("Invalid status transition: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(response);
    }

    @ExceptionHandler(OptimisticLockException.class)
    public ResponseEntity<ErrorResponse> handleOptimisticLock(OptimisticLockException ex) {
        return buildResponse(HttpStatus.CONFLICT, ErrorResponse.ErrorCodeEnum.CONCURRENT_MODIFICATION, ex.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException ex) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
                .findFirst()
                .orElse("Validation failed");
        return buildResponse(HttpStatus.BAD_REQUEST, ErrorResponse.ErrorCodeEnum.VALIDATION_ERROR, message);
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ErrorResponse> handleAccessDenied(AccessDeniedException ex) {
        return buildResponse(HttpStatus.FORBIDDEN, ErrorResponse.ErrorCodeEnum.UNAUTHORIZED, ex.getMessage());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleGeneral(Exception ex) {
        log.error("Unexpected error", ex);
        return buildResponse(HttpStatus.INTERNAL_SERVER_ERROR, ErrorResponse.ErrorCodeEnum.INTERNAL_ERROR,
                "An unexpected error occurred");
    }

    private ResponseEntity<ErrorResponse> buildResponse(HttpStatus status,
                                                        ErrorResponse.ErrorCodeEnum errorCode,
                                                        String message) {
        ErrorResponse response = new ErrorResponse();
        response.setErrorCode(errorCode);
        response.setMessage(message);
        response.setTimestamp(OffsetDateTime.now(ZoneOffset.UTC));
        return ResponseEntity.status(status).body(response);
    }
}
