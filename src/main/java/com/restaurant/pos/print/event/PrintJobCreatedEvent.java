package com.restaurant.pos.print.event;

import java.util.UUID;

/**
 * Event published when a new print job is created for real-time SSE streaming.
 */
public record PrintJobCreatedEvent(
        UUID clientId,
        UUID orgId,
        UUID printJobId,
        String jobKind,
        String printerProfileId
) {}
