package com.restaurant.pos.common.service;

import com.restaurant.pos.common.context.ContextProvider;
import com.restaurant.pos.common.entity.AuditLog;
import com.restaurant.pos.common.repository.AuditLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Enterprise Audit Logging Service.
 * Persists high-value system operations for regulatory and troubleshooting audit trails.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuditLogService {

    private final AuditLogRepository auditLogRepository;
    private final ContextProvider contextProvider;

    /**
     * Logs an operational action for an entity.
     * Enforces Propagation.REQUIRED to inherit and durably write in the active database transaction.
     */
    @Transactional(propagation = Propagation.REQUIRED)
    public void logAction(String action, String entityName, String entityId) {
        try {
            AuditLog auditLog = AuditLog.builder()
                    .action(action)
                    .entityName(entityName)
                    .entityId(entityId)
                    .orgId(contextProvider.getCurrentOrg())
                    .terminalId(contextProvider.getCurrentTerminal())
                    .userId(contextProvider.getCurrentUserId())
                    .build();

            // Set client ID from tenant context
            auditLog.setClientId(contextProvider.getCurrentTenant());

            AuditLog saved = auditLogRepository.save(auditLog);
            log.debug("Audit log recorded | id={} | action={} | entity={} | entityId={}", 
                    saved.getId(), action, entityName, entityId);
        } catch (Exception e) {
            // Log audit failures warning-level to prevent business interruption, 
            // but fail-open so operations do not crash if audit logging encounters transient db errors.
            log.warn("Failed to write audit log entry | action={} | entity={} | entityId={}", 
                    action, entityName, entityId, e);
        }
    }
}
