package com.engineeringprooflabs.datamodeling.orders;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApiError> validation(MethodArgumentNotValidException exception) {
        Map<String, String> fields = new LinkedHashMap<>();
        exception.getBindingResult().getFieldErrors().forEach(error ->
                fields.putIfAbsent(error.getField(), error.getDefaultMessage()));
        return ResponseEntity.badRequest().body(new ApiError("VALIDATION_ERROR", "The request is invalid.", fields));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ApiError> malformed(HttpMessageNotReadableException exception) {
        return ResponseEntity.badRequest().body(new ApiError("INVALID_JSON", "The request body is not valid JSON.", Map.of()));
    }

    @ExceptionHandler(OrderNotFoundException.class)
    ResponseEntity<ApiError> orderNotFound(OrderNotFoundException exception) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ApiError("ORDER_NOT_FOUND", "The order does not exist.", Map.of()));
    }

    @ExceptionHandler(CustomerNotFoundException.class)
    ResponseEntity<ApiError> customerNotFound(CustomerNotFoundException exception) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ApiError("CUSTOMER_NOT_FOUND", "The customer does not exist.", Map.of()));
    }

    @ExceptionHandler(OrderAlreadyConfirmedException.class)
    ResponseEntity<ApiError> alreadyConfirmed(OrderAlreadyConfirmedException exception) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ApiError("ORDER_ALREADY_CONFIRMED", "The order is already confirmed.", Map.of()));
    }

    @ExceptionHandler({InvalidCursorException.class, InvalidPageSizeException.class})
    ResponseEntity<ApiError> invalidPagination(RuntimeException exception) {
        String code = exception instanceof InvalidCursorException ? "INVALID_CURSOR" : "INVALID_LIMIT";
        String message = exception instanceof InvalidCursorException
                ? "The cursor is invalid for this query."
                : "The limit must be between 1 and 50.";
        return ResponseEntity.badRequest().body(new ApiError(code, message, Map.of()));
    }

    @ExceptionHandler(FailureInjectionDisabledException.class)
    ResponseEntity<ApiError> failureInjectionDisabled(FailureInjectionDisabledException exception) {
        return ResponseEntity.badRequest()
                .body(new ApiError("FAILURE_INJECTION_DISABLED", "Lab failure injection is disabled.", Map.of()));
    }

    @ExceptionHandler(InjectedWriteFailureException.class)
    ResponseEntity<ApiError> injectedFailure(InjectedWriteFailureException exception) {
        return ResponseEntity.internalServerError()
                .body(new ApiError("INJECTED_WRITE_FAILURE", "The lab injected a write failure.", Map.of()));
    }

    @ExceptionHandler(ArithmeticException.class)
    ResponseEntity<ApiError> arithmetic(ArithmeticException exception) {
        return ResponseEntity.badRequest()
                .body(new ApiError("ORDER_TOTAL_OVERFLOW", "The order total is outside the supported range.", Map.of()));
    }

    public record ApiError(String code, String message, Map<String, String> fields) {
    }
}
