package com.restaurant.pos.common.repository;

import com.restaurant.pos.common.entity.AuditLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.UUID;

/**
 * Repository interface for managing AuditLog entities.
 */
@Repository
public interface AuditLogRepository extends JpaRepository<AuditLog, UUID> {
}
