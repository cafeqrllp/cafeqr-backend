package com.restaurant.pos.realtime;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Tenant-scoped Server-Sent Events hub that tells clients "something changed" (orders, tables,
 * stock, products) so they re-fetch through the existing REST endpoints instead of polling.
 * <p>
 * Signal-only: payloads carry no business data, so nothing tenant-sensitive leaves the server
 * and the public stream needs no data authorisation. Holds no DB connections.
 * Bursts of changes are coalesced into one signal per branch per {@link #COALESCE_MS}.
 */
@Slf4j
@Service
@lombok.RequiredArgsConstructor
public class RealtimeChangeService {

    private static final long SSE_TIMEOUT_MS = 1_800_000L; // 30 minutes; EventSource auto-reconnects
    private static final long COALESCE_MS = 300L;
    /** Tenant-wide changes (shared products/stock with no branch) fan out to every branch, so batch them longer. */
    private static final long COALESCE_TENANT_WIDE_MS = 2_000L;

    @Value("${app.realtime.max-streams-per-client:5000}")
    private int maxStreamsPerClient;

    @Value("${app.realtime.max-streams-total:8000}")
    private int maxStreamsTotal;

    private record Subscriber(UUID orgId, SseEmitter emitter) {}

    private static final UUID NO_ORG = new UUID(0L, 0L);

    private final Map<UUID, List<Subscriber>> subscribersByClient = new ConcurrentHashMap<>();
    /** clientId -> pending orgIds (NO_ORG when the change had no org). Presence means a flush is scheduled. */
    private final Map<UUID, Set<UUID>> pending = new ConcurrentHashMap<>();
    private final AtomicInteger total = new AtomicInteger();
    private final com.restaurant.pos.qrmenu.query.QrMenuCacheService menuCache;
    private ScheduledExecutorService coalescer;

    @PostConstruct
    void init() {
        coalescer = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "realtime-coalescer");
            t.setDaemon(true);
            return t;
        });
        RealtimeChangeEntityListener.register(this);
    }

    @PreDestroy
    void shutdown() {
        RealtimeChangeEntityListener.register(null);
        coalescer.shutdownNow();
    }

    /** @return the emitter, or {@code null} when connection limits are reached (caller should answer 503). */
    public SseEmitter subscribe(UUID clientId, UUID orgId) {
        if (clientId == null) {
            throw new IllegalArgumentException("clientId must not be null for realtime subscription");
        }
        List<Subscriber> list = subscribersByClient.computeIfAbsent(clientId, k -> new CopyOnWriteArrayList<>());
        if (total.get() >= maxStreamsTotal || list.size() >= maxStreamsPerClient) {
            log.warn("Realtime stream limit reached (client={}, clientStreams={}, total={})",
                    clientId, list.size(), total.get());
            return null;
        }

        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);
        Subscriber sub = new Subscriber(orgId, emitter);
        list.add(sub);
        total.incrementAndGet();

        Runnable cleanup = () -> remove(clientId, sub);
        emitter.onCompletion(cleanup);
        emitter.onTimeout(cleanup);
        emitter.onError(e -> cleanup.run());

        try {
            emitter.send(SseEmitter.event().name("init").data("connected"));
        } catch (IOException | IllegalStateException e) {
            cleanup.run();
        }
        return emitter;
    }

    /** Requests a "changed" signal for the branch. Cheap and non-blocking; safe to call from anywhere. */
    public void notifyChanged(UUID clientId, UUID orgId) {
        if (clientId == null) {
            return;
        }
        menuCache.invalidate(clientId, orgId); // before the ping, so the client's re-fetch is never served stale
        if (!subscribersByClient.containsKey(clientId)) {
            return;
        }
        UUID key = orgId != null ? orgId : NO_ORG;
        boolean[] schedule = {false};
        // compute() and remove() in flush() are atomic per key, so a change can never land in an already-flushed set.
        pending.compute(clientId, (k, set) -> {
            if (set == null) {
                set = ConcurrentHashMap.newKeySet();
                schedule[0] = true;
            }
            set.add(key);
            return set;
        });
        if (schedule[0]) {
            long delay = orgId == null ? COALESCE_TENANT_WIDE_MS : COALESCE_MS;
            coalescer.schedule(() -> flush(clientId), delay, TimeUnit.MILLISECONDS);
        }
    }

    private void flush(UUID clientId) {
        Set<UUID> orgs = pending.remove(clientId);
        List<Subscriber> subs = subscribersByClient.get(clientId);
        if (orgs == null || orgs.isEmpty() || subs == null) {
            return;
        }
        boolean anyOrg = orgs.contains(NO_ORG);
        for (Subscriber sub : subs) {
            if (!anyOrg && sub.orgId() != null && !orgs.contains(sub.orgId())) {
                continue;
            }
            try {
                sub.emitter().send(SseEmitter.event().name("changed").data("1"));
            } catch (Exception e) {
                remove(clientId, sub);
            }
        }
    }

    /** Keeps proxies (Cloudflare/Caddy/Nginx) from closing idle streams. */
    @Scheduled(fixedRate = 15_000)
    public void heartbeat() {
        subscribersByClient.forEach((clientId, subs) -> {
            for (Subscriber sub : subs) {
                try {
                    sub.emitter().send(SseEmitter.event().comment("ping"));
                } catch (Exception e) {
                    remove(clientId, sub);
                }
            }
        });
    }

    private void remove(UUID clientId, Subscriber sub) {
        List<Subscriber> subs = subscribersByClient.get(clientId);
        if (subs != null && subs.remove(sub)) {
            total.decrementAndGet();
            if (subs.isEmpty()) {
                subscribersByClient.remove(clientId, subs);
            }
        }
    }
}
