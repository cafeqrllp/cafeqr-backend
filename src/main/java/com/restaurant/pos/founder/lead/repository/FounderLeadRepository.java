package com.restaurant.pos.founder.lead.repository;

import com.restaurant.pos.founder.lead.domain.FounderLead;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface FounderLeadRepository extends JpaRepository<FounderLead, UUID> {

    List<FounderLead> findAllByOrderByCreatedAtDesc();

    List<FounderLead> findAllByStatusOrderByCreatedAtDesc(String status);

    @Query("SELECT l FROM FounderLead l WHERE l.primaryPhone = :phone OR l.whatsappNumber = :phone OR l.alternatePhone = :phone")
    List<FounderLead> findAllByAnyPhone(@Param("phone") String phone);

    @Query("SELECT l FROM FounderLead l WHERE l.nextFollowUpAt < :now AND l.status NOT IN ('PAID_ACTIVE', 'LOST') ORDER BY l.nextFollowUpAt ASC")
    List<FounderLead> findOverdueFollowUps(@Param("now") LocalDateTime now);

    @Query("SELECT l FROM FounderLead l WHERE l.nextFollowUpAt >= :start AND l.nextFollowUpAt <= :end AND l.status NOT IN ('PAID_ACTIVE', 'LOST') ORDER BY l.nextFollowUpAt ASC")
    List<FounderLead> findDueFollowUpsBetween(@Param("start") LocalDateTime start, @Param("end") LocalDateTime end);

    Optional<FounderLead> findByAssignedOrgId(UUID assignedOrgId);

    Optional<FounderLead> findByAssignedClientId(UUID assignedClientId);

    long countByStatus(String status);
}
