package com.restaurant.pos.pos.sale.query;

import java.util.concurrent.Executor;

/**
 * @deprecated Moved to {@link com.restaurant.pos.cache.loader.SingleFlightCacheLoader}.
 * Retained for backward compatibility.
 */
@Deprecated
public class SingleFlightCacheLoader extends com.restaurant.pos.cache.loader.SingleFlightCacheLoader {

    public SingleFlightCacheLoader(Executor executor) {
        super(executor);
    }
}
