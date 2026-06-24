package com.smartTriage.smartTriage_server.common.exception;

import com.smartTriage.smartTriage_server.common.dto.ApiResponse;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.HashMap;
import java.util.Map;

/**
 * Global exception handler for all SmartTriage REST endpoints.
 * Ensures consistent error response format across the entire system.
 * In a life-critical system, clear error communication is essential.
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<ApiResponse<Void>> handleResourceNotFound(ResourceNotFoundException ex) {
        log.warn("Resource not found: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiResponse.error(ex.getMessage()));
    }

    @ExceptionHandler(DuplicateResourceException.class)
    public ResponseEntity<ApiResponse<Void>> handleDuplicateResource(DuplicateResourceException ex) {
        log.warn("Duplicate resource: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiResponse.error(ex.getMessage()));
    }

    @ExceptionHandler(IdentityConflictException.class)
    public ResponseEntity<ApiResponse<Void>> handleIdentityConflict(IdentityConflictException ex) {
        log.warn("Cross-hospital identity conflict: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiResponse.error(ex.getMessage()));
    }

    @ExceptionHandler(ClinicalBusinessException.class)
    public ResponseEntity<ApiResponse<Void>> handleClinicalBusiness(ClinicalBusinessException ex) {
        log.error("Clinical business rule violation: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                .body(ApiResponse.error(ex.getMessage()));
    }

    @ExceptionHandler(HospitalAccessDeniedException.class)
    public ResponseEntity<ApiResponse<Void>> handleHospitalAccessDenied(HospitalAccessDeniedException ex) {
        log.warn("Hospital access denied: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(ApiResponse.error(ex.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Map<String, String>>> handleValidation(MethodArgumentNotValidException ex) {
        Map<String, String> errors = new HashMap<>();
        ex.getBindingResult().getAllErrors().forEach(error -> {
            String fieldName = ((FieldError) error).getField();
            String message = error.getDefaultMessage();
            errors.put(fieldName, message);
        });
        log.warn("Validation failed: {}", errors);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiResponse.validationError(errors));
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiResponse<Void>> handleConstraintViolation(ConstraintViolationException ex) {
        log.warn("Constraint violation: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiResponse.error(ex.getMessage()));
    }

    @ExceptionHandler(BadCredentialsException.class)
    public ResponseEntity<ApiResponse<Void>> handleBadCredentials(BadCredentialsException ex) {
        log.warn("Authentication failed: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(ApiResponse.error("Invalid email or password."));
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiResponse<Void>> handleAccessDenied(AccessDeniedException ex) {
        log.warn("Access denied: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(ApiResponse.error("Access denied. Insufficient permissions."));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ApiResponse<Void>> handleIllegalState(IllegalStateException ex) {
        log.warn("Illegal state: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiResponse.error(ex.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiResponse<Void>> handleIllegalArgument(IllegalArgumentException ex) {
        log.warn("Illegal argument: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiResponse.error(ex.getMessage()));
    }

    @ExceptionHandler(OptimisticLockingFailureException.class)
    public ResponseEntity<ApiResponse<Void>> handleOptimisticLock(OptimisticLockingFailureException ex) {
        log.warn("Optimistic lock conflict: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiResponse.error("The record was modified concurrently. Please try again."));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Void>> handleUnreadable(HttpMessageNotReadableException ex) {
        log.warn("Unreadable request body: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiResponse.error("Invalid request format. Please check your input."));
    }

    /**
     * Database integrity violations (unique / foreign-key / check constraints).
     * <p>These are almost always a business-rule collision — e.g. two nurses
     * racing to assign the same user to a zone, a duplicate email, or a
     * stale row blocking a partial-unique index. A 500 ("contact system
     * administrator") is the wrong response: the caller did nothing that
     * warrants paging a sysadmin, they just hit a domain rule. Mapping to
     * 409 CONFLICT with a concrete hint lets the UI show a useful error
     * and lets the user retry intelligently.
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiResponse<Void>> handleDataIntegrity(DataIntegrityViolationException ex) {
        log.warn("Data integrity violation: {}", ex.getMostSpecificCause().getMessage());
        String detail = friendlyConstraintMessage(ex);
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiResponse.error(detail));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleGeneral(Exception ex) {
        log.error("Unhandled exception", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.error("An unexpected error occurred. Contact system administrator."));
    }

    /**
     * Best-effort translation of a Postgres constraint-violation message
     * into clinical-user-friendly language. Keeps the technical constraint
     * name out of the UI while preserving enough signal for the user to
     * understand what to do next.
     */
    private String friendlyConstraintMessage(DataIntegrityViolationException ex) {
        String raw = ex.getMostSpecificCause() != null
                ? String.valueOf(ex.getMostSpecificCause().getMessage())
                : ex.getMessage();
        if (raw == null) {
            return "The request conflicts with existing data. Please refresh and try again.";
        }
        String lower = raw.toLowerCase();
        if (lower.contains("uk_shift_user_date_period_active")
                || lower.contains("uq_shift_user_date_period")) {
            return "That staff member already has an active assignment for this shift. "
                    + "End the current assignment before starting a new one.";
        }
        if (lower.contains("uk_shift_template_user")) {
            // Belt-and-braces: ShiftTemplateService.update now flushes
            // between clear() and addAll() to avoid this, but if it
            // ever trips again the operator gets a real hint.
            return "That staff member is listed twice in this template. "
                    + "Refresh the page and edit the existing row instead of adding a new one.";
        }
        if (lower.contains("uk_shift_template_lead")) {
            return "Only one shift-lead is allowed per template. Clear the existing lead first.";
        }
        if (lower.contains("uk_bed_one_active_visit")) {
            return "That bed is already occupied. Move or discharge the current patient first.";
        }
        if (lower.contains("uk_visit_one_bed")) {
            return "That patient is already placed in a bed. Transfer instead of re-placing.";
        }
        if (lower.contains("uk_device_one_bed")) {
            return "That device is already assigned to a different bed.";
        }
        if (lower.contains("uk_iot_device_serial")) {
            return "A device with that serial number is already registered.";
        }
        if (lower.contains("uk_iot_device_api_key")) {
            return "That API key is already in use by another device.";
        }
        if (lower.contains("users_email_key") || lower.contains("idx_user_email")) {
            return "A user with that email already exists.";
        }
        return "The request conflicts with existing data. Please refresh and try again.";
    }
}
