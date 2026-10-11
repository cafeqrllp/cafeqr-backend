package com.restaurant.pos.print.service;

import com.restaurant.pos.print.event.PrintJobCreatedEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * High-performance, zero-database-holding Server-Sent Events (SSE) manager
 * for real-time printer notifications.
 * <p>
 * Completely isolated from JPA/Hibernate transactions to ensure zero
 * database connection leakage.
 */
@Slf4j
@Service
/**
 * Server-Sent Events service for real-time printer dispatch.
 */
public class PrintSseService {

    private static final long SSE_TIMEOUT_MS = 1_800_000L; // 30 minutes

    // Map of clientId -> List of active SseEmitters
    private final Map<UUID, List<SseEmitter>> emittersByClient = new ConcurrentHashMap<>();

    public SseEmitter subscribe(UUID clientId, UUID orgId) {
        if (clientId == null) {
            throw new IllegalArgumentException("clientId must not be null for SSE subscription");
        }

        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);
        emittersByClient.computeIfAbsent(clientId, k -> new CopyOnWriteArrayList<>()).add(emitter);

        Runnable cleanup = () -> removeEmitter(clientId, emitter);
        emitter.onCompletion(cleanup);
        emitter.onTimeout(cleanup);
        emitter.onError(e -> cleanup.run());

        try {
            // Immediate handshake to establish the stream
            emitter.send(SseEmitter.event()
                    .name("init")
                    .data(Map.of(
                            "status", "connected",
                            "clientId", clientId.toString(),
                            "orgId", orgId != null ? orgId.toString() : ""
                    )));
        } catch (IOException e) {
            log.debug("Failed to send init event to SSE client: {}", e.getMessage());
            cleanup.run();
        }

        log.debug("Registered SSE printer client for tenant: {}", clientId);
        return emitter;
    }

    public void broadcastPrintJob(PrintJobCreatedEvent event) {
        if (event == null || event.clientId() == null) {
            return;
        }

        List<SseEmitter> emitters = emittersByClient.get(event.clientId());
        if (emitters == null || emitters.isEmpty()) {
            return;
        }

        List<SseEmitter> deadEmitters = new ArrayList<>();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("id", event.printJobId() != null ? event.printJobId().toString() : "");
        payload.put("jobKind", event.jobKind() != null ? event.jobKind() : "");
        payload.put("orgId", event.orgId() != null ? event.orgId().toString() : "");
        if (event.printerProfileId() != null) {
            payload.put("printerProfileId", event.printerProfileId());
        }

        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(SseEmitter.event()
                        .name("NEW_JOB")
                        .data(payload));
            } catch (Exception e) {
                deadEmitters.add(emitter);
            }
        }

        if (!deadEmitters.isEmpty()) {
            emitters.removeAll(deadEmitters);
        }
    }

    /**
     * Heartbeat keep-alive every 15 seconds.
     * Prevents Cloudflare and Caddy from closing idle streams (100s timeout).
     */
    @Scheduled(fixedRate = 15000)
    public void sendHeartbeat() {
        if (emittersByClient.isEmpty()) {
            return;
        }

        for (Map.Entry<UUID, List<SseEmitter>> entry : emittersByClient.entrySet()) {
            List<SseEmitter> emitters = entry.getValue();
            List<SseEmitter> deadEmitters = new ArrayList<>();

            for (SseEmitter emitter : emitters) {
                try {
                    emitter.send(SseEmitter.event().comment("ping"));
                } catch (Exception e) {
                    deadEmitters.add(emitter);
                }
            }

            if (!deadEmitters.isEmpty()) {
                emitters.removeAll(deadEmitters);
            }
        }
    }

    private void removeEmitter(UUID clientId, SseEmitter emitter) {
        List<SseEmitter> emitters = emittersByClient.get(clientId);
        if (emitters != null) {
            emitters.remove(emitter);
            if (emitters.isEmpty()) {
                emittersByClient.remove(clientId);
            }
        }
    }
}
