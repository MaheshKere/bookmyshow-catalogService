package com.bookmyshow.booking.exception;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.*;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;
import java.util.LinkedHashMap;
import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {
    @ExceptionHandler(ConflictException.class)
    public ProblemDetail handleConflict(ConflictException exception) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, exception.getMessage());
    }

    @ExceptionHandler({org.springframework.dao.OptimisticLockingFailureException.class,
            org.springframework.dao.PessimisticLockingFailureException.class,
            jakarta.persistence.OptimisticLockException.class,
            jakarta.persistence.PessimisticLockException.class,
            jakarta.persistence.LockTimeoutException.class})
    public ProblemDetail handleConcurrency(Exception exception) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT,
                "Concurrent modification or lock conflict. Read the current state and retry.");
    }

    @ExceptionHandler(BusinessValidationException.class)
    public ProblemDetail handleBusinessValidation(BusinessValidationException exception) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, exception.getMessage());
    }

    @ExceptionHandler(ResourceNotFoundException.class)
    public ProblemDetail handleNotFound(ResourceNotFoundException exception) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, exception.getMessage());
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ProblemDetail handleIntegrityConflict(DataIntegrityViolationException exception) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT,
                "The operation conflicts with a database constraint.");
    }

    @Override
    protected ResponseEntity<Object> handleHandlerMethodValidationException(
            org.springframework.web.method.annotation.HandlerMethodValidationException exception,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        if (exception.isForReturnValue()) {
            return super.handleHandlerMethodValidationException(exception, headers, status, request);
        }
        Map<String, String> errors = new LinkedHashMap<>();
        exception.getParameterValidationResults().forEach(result -> {
            if (result instanceof org.springframework.validation.method.ParameterErrors fieldErrors) {
                fieldErrors.getFieldErrors().forEach(error ->
                        errors.putIfAbsent(error.getField(), error.getDefaultMessage()));
            } else {
                String name = result.getMethodParameter().getParameterName();
                result.getResolvableErrors().forEach(error ->
                        errors.putIfAbsent(name == null ? "parameter" : name, error.getDefaultMessage()));
            }
        });
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST,
                "Request validation failed.");
        problem.setProperty("errors", errors);
        return handleExceptionInternal(exception, problem, headers, status, request);
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException exception, HttpHeaders headers,
            HttpStatusCode status, WebRequest request) {
        Map<String, String> errors = new LinkedHashMap<>();
        exception.getBindingResult().getFieldErrors()
                .forEach(error -> errors.putIfAbsent(error.getField(), error.getDefaultMessage()));
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST,
                "Request validation failed.");
        problem.setProperty("errors", errors);
        return handleExceptionInternal(exception, problem, headers, status, request);
    }
}
