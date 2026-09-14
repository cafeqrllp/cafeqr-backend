package com.restaurant.pos.outbox.processor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.restaurant.pos.outbox.domain.OutboxEvent;
import com.restaurant.pos.outbox.repository.OutboxRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Scheduled outbox processor that claims and dispatches pending events.
 * <p>
 * Event handlers are registered at startup by each consumer via
 * {@link #registerHandler(String, Consumer)}. The processor runs every
 * second, claims up to {@value #BATCH_SIZE} events using
 * {@code FOR UPDATE SKIP LOCKED}, and dispatches each to its registered handler.
 * <p>
 * Failed events are retried with exponential backoff (capped at 1 hour).
 * After {@value #MAX_ATTEMPTS} failures, an event is marked as {@code FAILED}.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OutboxProcessor {

    private static final int BATCH_SIZE = 100;
    private static final int MAX_ATTEMPTS = 10;
    private static final Duration BASE_BACKOFF = Duration.ofSeconds(5);
    private static final Duration MAX_BACKOFF = Duration.ofHours(1);

    private final OutboxRepository outboxRepository;
    private final ObjectMapper objectMapper;

    private final Map<String, Consumer<OutboxEvent>> handlers = new ConcurrentHashMap<>();

    /**
     * Registers an event handler for a specific event type.
     * Called during bean initialization by each outbox consumer.
     *
     * @param eventType the event type to handle (e.g., "ORDER_SETTLED")
     * @param handler   the handler function
     */
    public void registerHandler(String eventType, Consumer<OutboxEvent> handler) {
        handlers.put(eventType, handler);
        log.info("Outbox handler registered for event type: {}", eventType);
    }

    /**
     * Claims and processes a batch of pending outbox events.
     * Runs every 1 second with a 5-second initial delay.
     */
    @Scheduled(fixedDelay = 1000, initialDelay = 5000)
    public void processOutbox() {
        List<OutboxEvent> events = claimEvents();
        if (events.isEmpty()) {
            return;
        }

        log.debug("Outbox processor claimed {} events", events.size());

        for (OutboxEvent event : events) {
            processEvent(event);
        }
    }

    @Transactional
    List<OutboxEvent> claimEvents() {
        try {
            List<OutboxEvent> events = outboxRepository.claimPendingEvents(Instant.now(), BATCH_SIZE);
            for (OutboxEvent event : events) {
                event.setStatus("PROCESSING");
            }
            return events;
        } catch (Exception e) {
            log.warn("Failed to claim outbox events: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * Recovers events stuck in PROCESSING status (e.g. from crashed worker nodes).
     * Runs every 60 seconds.
     */
    @Scheduled(fixedDelay = 60000, initialDelay = 30000)
    @Transactional
    public void recoverStaleProcessing() {
        try {
            Instant cutoff = Instant.now().minus(Duration.ofMinutes(5));
            int recovered = outboxRepository.recoverStaleProcessing(cutoff);
            if (recovered > 0) {
                log.warn("Outbox recovery: reset {} stale PROCESSING events back to PENDING", recovered);
            }
        } catch (Exception e) {
            log.warn("Failed to recover stale processing outbox events: {}", e.getMessage());
        }
    }

    private void processEvent(OutboxEvent event) {
        Consumer<OutboxEvent> handler = handlers.get(event.getEventType());
        if (handler == null) {
            log.warn("No handler registered for outbox event type '{}', marking as COMPLETED (no-op)",
                    event.getEventType());
            markCompleted(event);
            return;
        }

        try {
            handler.accept(event);
            markCompleted(event);
        } catch (Exception e) {
            handleFailure(event, e);
        }
    }

    private void markCompleted(OutboxEvent event) {
        try {
            event.setStatus("COMPLETED");
            event.setProcessedAt(Instant.now());
            event.setLastError(null);
            outboxRepository.save(event);
        } catch (Exception e) {
            log.error("Failed to mark outbox event {} as COMPLETED", event.getId(), e);
        }
    }

    private void handleFailure(OutboxEvent event, Exception error) {
        int nextAttempt = event.getAttempts() + 1;
        event.setAttempts(nextAttempt);
        event.setLastError(truncateError(error));

        if (nextAttempt >= MAX_ATTEMPTS) {
            event.setStatus("FAILED");
            log.error("Outbox event {} exhausted {} attempts, marking as FAILED. eventType={}, aggregateId={}",
                    event.getId(), MAX_ATTEMPTS, event.getEventType(), event.getAggregateId());
        } else {
            // Exponential backoff: 5s, 10s, 20s, 40s, ... capped at 1 hour
            Duration backoff = BASE_BACKOFF.multipliedBy((long) Math.pow(2, nextAttempt - 1));
            if (backoff.compareTo(MAX_BACKOFF) > 0) {
                backoff = MAX_BACKOFF;
            }
            event.setAvailableAt(Instant.now().plus(backoff));
            event.setStatus("PENDING");
            log.warn("Outbox event {} failed (attempt {}/{}), retrying in {}s. eventType={}, error={}",
                    event.getId(), nextAttempt, MAX_ATTEMPTS, backoff.toSeconds(),
                    event.getEventType(), error.getMessage());
        }

        try {
            outboxRepository.save(event);
        } catch (Exception e) {
            log.error("Failed to update outbox event {} after failure", event.getId(), e);
        }
    }

    /**
     * Scheduled cleanup: removes completed events older than 7 days.
     * Runs daily at 03:00 AM.
     */
    @Scheduled(cron = "0 0 3 * * *")
    @Transactional
    public void cleanupCompletedEvents() {
        Instant cutoff = Instant.now().minus(Duration.ofDays(7));
        int deleted = outboxRepository.deleteCompletedBefore(cutoff);
        if (deleted > 0) {
            log.info("Outbox cleanup: removed {} completed events older than 7 days", deleted);
        }
    }

    /**
     * Parses the JSON payload of an outbox event into a {@link JsonNode}.
     * Utility for consumers that need to inspect the payload.
     */
    public JsonNode parsePayload(OutboxEvent event) {
        try {
            return objectMapper.readTree(event.getPayload());
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse outbox event payload for event " + event.getId(), e);
        }
    }

    private String truncateError(Exception e) {
        String msg = e.getMessage();
        if (msg == null) {
            msg = e.getClass().getSimpleName();
        }
        return msg.length() > 1000 ? msg.substring(0, 1000) : msg;
    }
}
