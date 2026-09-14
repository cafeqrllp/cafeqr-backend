package com.restaurant.pos.pos.sale.config;

import com.restaurant.pos.cache.loader.SingleFlightCacheLoader;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.CustomizableThreadFactory;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Configuration for POS Sales Screen execution and thread pools.
 * Configurable via application properties for tuning DB concurrency and POS terminal load.
 */
@Configuration
public class PosSaleConfig {

    @Bean(name = "posSalesScreenExecutor", destroyMethod = "shutdown")
    public ExecutorService posSalesScreenExecutor(
            @Value("${pos.sale.screen.thread-pool.core-size:4}") int coreSize,
            @Value("${pos.sale.screen.thread-pool.max-size:8}") int maxSize,
            @Value("${pos.sale.screen.thread-pool.queue-capacity:50}") int queueCapacity) {
        return new ThreadPoolExecutor(
                coreSize,
                maxSize,
                60L,
                TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(queueCapacity),
                new CustomizableThreadFactory("pos-sales-screen-"),
                new ThreadPoolExecutor.CallerRunsPolicy()
        );
    }

    @Bean
    public SingleFlightCacheLoader singleFlightCacheLoader(
            @Qualifier("posSalesScreenExecutor") ExecutorService executor) {
        return new SingleFlightCacheLoader(executor);
    }
}
