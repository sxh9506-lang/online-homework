package com.onlinehomework.common.api;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.http.converter.HttpMessageNotReadableException;

import java.net.URI;
import java.util.Objects;

@RestControllerAdvice
public class ApiExceptionHandler {
    @ExceptionHandler(DomainException.class)
    ResponseEntity<ApiError> handleDomain(DomainException exception, HttpServletRequest request) {
        return response(exception.status(), exception.code(), exception.getMessage(), exception.retryable(), request);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApiError> handleValidation(MethodArgumentNotValidException exception, HttpServletRequest request) {
        String detail = exception.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + " " + Objects.requireNonNullElse(error.getDefaultMessage(), "is invalid"))
                .findFirst().orElse("Request validation failed.");
        return response(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", detail, false, request);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    ResponseEntity<ApiError> handleConstraint(ConstraintViolationException exception, HttpServletRequest request) {
        return response(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Request validation failed.", false, request);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ApiError> handleUnreadable(HttpMessageNotReadableException exception, HttpServletRequest request) {
        return response(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "Request body is missing or malformed.", false, request);
    }

    private ResponseEntity<ApiError> response(HttpStatus status, String code, String detail, boolean retryable,
                                               HttpServletRequest request) {
        ApiError error = new ApiError(
                URI.create("about:blank"),
                status.getReasonPhrase(),
                status.value(),
                code,
                detail,
                retryable,
                request.getRequestURI());
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_PROBLEM_JSON).body(error);
    }
}
