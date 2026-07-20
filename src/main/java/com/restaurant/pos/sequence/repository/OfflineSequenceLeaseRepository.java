package com.restaurant.pos.sequence.repository;

import com.restaurant.pos.sequence.domain.DocumentType;
import com.restaurant.pos.sequence.domain.OfflineSequenceLease;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface OfflineSequenceLeaseRepository extends JpaRepository<OfflineSequenceLease, UUID> {

    List<OfflineSequenceLease> findByClientIdAndOrgIdAndTerminalIdAndStatusOrderByCreatedAtAsc(
            UUID clientId,
            UUID orgId,
            UUID terminalId,
            String status
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    List<OfflineSequenceLease> findByClientIdAndOrgIdAndTerminalIdAndDocumentTypeAndStatusOrderByStartNumberAsc(
            UUID clientId,
            UUID orgId,
            UUID terminalId,
            DocumentType documentType,
            String status
    );

    boolean existsByClientIdAndOrgIdAndDocumentTypeAndStartNumberLessThanEqualAndEndNumberGreaterThanEqual(
            UUID clientId,
            UUID orgId,
            DocumentType documentType,
            Long endNumber,
            Long startNumber
    );

    List<OfflineSequenceLease> findByClientIdAndOrgIdAndDocumentType(
            UUID clientId,
            UUID orgId,
            DocumentType documentType
    );
}
