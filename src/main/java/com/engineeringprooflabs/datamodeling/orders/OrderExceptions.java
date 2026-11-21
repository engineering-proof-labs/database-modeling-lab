package com.engineeringprooflabs.datamodeling.orders;

class OrderNotFoundException extends RuntimeException {
    OrderNotFoundException(String id) {
        super("Order not found: " + id);
    }
}

class CustomerNotFoundException extends RuntimeException {
    CustomerNotFoundException(String id) {
        super("Customer not found: " + id);
    }
}

class InjectedWriteFailureException extends RuntimeException {
    InjectedWriteFailureException() {
        super("Lab failure injected after inserting the order header.");
    }
}

class FailureInjectionDisabledException extends RuntimeException {
    FailureInjectionDisabledException() {
        super("Lab failure injection is disabled.");
    }
}

class OrderAlreadyConfirmedException extends RuntimeException {
    OrderAlreadyConfirmedException(String id) {
        super("Order is already confirmed: " + id);
    }
}

class InvalidCursorException extends RuntimeException {
    InvalidCursorException() {
        super("The cursor is invalid for this query.");
    }
}

class InvalidPageSizeException extends RuntimeException {
    InvalidPageSizeException() {
        super("The page size must be between 1 and 50.");
    }
}
