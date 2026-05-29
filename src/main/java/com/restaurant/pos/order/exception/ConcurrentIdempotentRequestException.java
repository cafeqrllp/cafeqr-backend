package com.restaurant.pos.order.exception;

public class ConcurrentIdempotentRequestException extends RuntimeException {
    public ConcurrentIdempotentRequestException(String message) {
        super(message);
    }
}
