package com.sam.apnasamaan.common.exception;

import org.springframework.http.HttpStatus;

/**
 * Base runtime exception for domain/business errors mapped cleanly to HTTP status codes.
 */
public class ApiException extends RuntimeException {

    private final HttpStatus status;

    public ApiException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }

    public HttpStatus getStatus() {
        return status;
    }
}
