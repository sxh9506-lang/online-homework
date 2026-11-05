package com.onlinehomework.common.api;

import org.springframework.http.HttpStatus;

public class DomainException extends RuntimeException {
    private final String code;
    private final HttpStatus status;
    private final boolean retryable;

    public DomainException(String code, HttpStatus status, String message) {
        this(code, status, message, false);
    }

    public DomainException(String code, HttpStatus status, String message, boolean retryable) {
        super(message);
        this.code = code;
        this.status = status;
        this.retryable = retryable;
    }

    public String code() {
        return code;
    }

    public HttpStatus status() {
        return status;
    }

    public boolean retryable() {
        return retryable;
    }

    public static DomainException notFound(String code, String message) {
        return new DomainException(code, HttpStatus.NOT_FOUND, message);
    }

    public static DomainException forbidden() {
        return new DomainException("FORBIDDEN", HttpStatus.FORBIDDEN, "You are not allowed to access this resource.");
    }

    public static DomainException conflict(String code, String message) {
        return new DomainException(code, HttpStatus.CONFLICT, message);
    }

    public static DomainException invalid(String code, String message) {
        return new DomainException(code, HttpStatus.BAD_REQUEST, message);
    }

    public static DomainException unprocessable(String code, String message) {
        return new DomainException(code, HttpStatus.UNPROCESSABLE_ENTITY, message);
    }
}
