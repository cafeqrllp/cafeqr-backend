package com.restaurant.pos.common.exception;

import lombok.Getter;
import java.util.List;

@Getter
public class StockWarningException extends RuntimeException {
    private final List<String> warnings;

    public StockWarningException(List<String> warnings) {
        super("STOCK_WARNING: " + String.join("; ", warnings != null ? warnings : List.of()));
        this.warnings = warnings;
    }
}
