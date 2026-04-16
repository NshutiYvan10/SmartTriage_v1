package com.smartTriage.smartTriage_server.common.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * Thrown when a clinical business rule is violated.
 * Examples: invalid triage transition, missing required vitals.
 */
@ResponseStatus(HttpStatus.UNPROCESSABLE_ENTITY)
public class ClinicalBusinessException extends RuntimeException {

    @SuppressWarnings("deprecation")
    public ClinicalBusinessException(String message) {
        super(message);
    }
}
