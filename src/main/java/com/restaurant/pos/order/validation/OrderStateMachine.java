package com.restaurant.pos.order.validation;

import com.restaurant.pos.common.exception.BusinessException;
import java.util.*;

/**
 * Enforces strict, state-machine validated transitions for Orders.
 * Prevents out-of-order transitions and ensures domain integrity.
 */
public class OrderStateMachine {

    private static final Map<String, Set<String>> ALLOWED_TRANSITIONS = new HashMap<>();

    static {
        // DRAFT can transition to anything since it's the initial/proposal stage
        ALLOWED_TRANSITIONS.put("DRAFT", new HashSet<>(Arrays.asList(
            "CONFIRMED", "IN_PROGRESS", "READY", "BILLED", "COMPLETED", "CANCELLED", "VOID"
        )));

        // CONFIRMED is officially accepted, can progress or be cancelled/voided
        ALLOWED_TRANSITIONS.put("CONFIRMED", new HashSet<>(Arrays.asList(
            "IN_PROGRESS", "READY", "BILLED", "COMPLETED", "CANCELLED", "VOID"
        )));

        // IN_PROGRESS is actively being worked on (e.g. in kitchen)
        ALLOWED_TRANSITIONS.put("IN_PROGRESS", new HashSet<>(Arrays.asList(
            "READY", "BILLED", "COMPLETED", "CANCELLED", "VOID"
        )));

        // READY is ready for delivery/pickup
        ALLOWED_TRANSITIONS.put("READY", new HashSet<>(Arrays.asList(
            "BILLED", "COMPLETED", "CANCELLED", "VOID"
        )));

        // BILLED is invoice generated, waiting for payment/settlement
        ALLOWED_TRANSITIONS.put("BILLED", new HashSet<>(Arrays.asList(
            "COMPLETED", "CANCELLED", "VOID"
        )));

        // COMPLETED is paid and delivered. It can be voided or cancelled
        ALLOWED_TRANSITIONS.put("COMPLETED", new HashSet<>(Arrays.asList(
            "VOID", "CANCELLED"
        )));

        // CANCELLED is a terminal state. No transitions allowed.
        ALLOWED_TRANSITIONS.put("CANCELLED", Collections.emptySet());

        // VOID is a terminal state. No transitions allowed.
        ALLOWED_TRANSITIONS.put("VOID", Collections.emptySet());
    }

    /**
     * Validates if a transition from currentStatus to targetStatus is allowed.
     * Throws BusinessException if the transition is invalid.
     */
    public static void validateTransition(String currentStatus, String targetStatus) {
        if (targetStatus == null) {
            return; // No target status specified, nothing to validate
        }

        String current = currentStatus == null ? "DRAFT" : currentStatus.trim().toUpperCase();
        String target = targetStatus.trim().toUpperCase();

        if (current.equals(target)) {
            return; // No-op state transition is always allowed
        }

        Set<String> allowed = ALLOWED_TRANSITIONS.get(current);
        if (allowed == null || !allowed.contains(target)) {
            throw new BusinessException(String.format(
                "Invalid order status transition from '%s' to '%s'.", current, target
            ));
        }
    }
}
