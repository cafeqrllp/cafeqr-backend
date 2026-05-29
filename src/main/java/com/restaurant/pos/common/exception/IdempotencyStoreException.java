package com.restaurant.pos.common.exception;

/**
 * Exception thrown when the idempotency backing store encounters a persistent
 * connection failure, serialization failure, or storage-level constraint violation.
 * Used to safeguard business workflows against data-loss and split-brain scenarios.
 */
public class IdempotencyStoreException extends RuntimeException {
    
    public IdempotencyStoreException(String message, Throwable cause) {
        super(message, cause);
    }
    
    public IdempotencyStoreException(String message) {
        super(message);
    }
}
