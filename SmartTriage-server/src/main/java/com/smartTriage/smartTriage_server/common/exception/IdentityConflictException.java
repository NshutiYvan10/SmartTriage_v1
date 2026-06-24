package com.smartTriage.smartTriage_server.common.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * Thrown when two cross-hospital identity anchors cannot be reconciled automatically — e.g. a
 * national ID and an RFID card that already point at DIFFERENT {@code PersonIdentity} rows, or a
 * patient who already has a different card on file. We deliberately reject rather than auto-merge:
 * a wrong identity merge would surface another patient's allergies/history (a safety incident).
 * Maps to HTTP 409 Conflict (see {@code GlobalExceptionHandler}).
 */
@ResponseStatus(HttpStatus.CONFLICT)
public class IdentityConflictException extends RuntimeException {

    public IdentityConflictException(String message) {
        super(message);
    }
}
