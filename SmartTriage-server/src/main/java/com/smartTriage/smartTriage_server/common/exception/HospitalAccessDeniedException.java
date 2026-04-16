package com.smartTriage.smartTriage_server.common.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * Thrown when access to a hospital-scoped resource is denied.
 */
@ResponseStatus(HttpStatus.FORBIDDEN)
public class HospitalAccessDeniedException extends RuntimeException {

    public HospitalAccessDeniedException() {
        super("Access denied: You do not have permission to access this hospital's resources.");
    }

    public HospitalAccessDeniedException(String message) {
        super(message);
    }
}
