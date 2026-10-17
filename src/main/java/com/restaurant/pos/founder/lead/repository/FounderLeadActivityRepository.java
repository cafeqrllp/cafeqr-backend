package com.restaurant.pos.founder.lead.repository;

import com.restaurant.pos.founder.lead.domain.FounderLeadActivity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface FounderLeadActivityRepository extends JpaRepository<FounderLeadActivity, UUID> {
    List<FounderLeadActivity> findAllByLeadIdOrderByCreatedAtDesc(UUID leadId);
}
