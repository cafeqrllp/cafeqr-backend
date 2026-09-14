package com.restaurant.pos.hr.repository;

import com.restaurant.pos.hr.entity.PayrollRun;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface PayrollRunRepository extends JpaRepository<PayrollRun, UUID> {
    
    @Query("SELECT p FROM PayrollRun p WHERE p.clientId = :clientId AND (:orgId IS NULL OR p.orgId = :orgId)")
    List<PayrollRun> findByClientIdAndOrgId(@Param("clientId") UUID clientId, @Param("orgId") UUID orgId);

    @Query("SELECT p FROM PayrollRun p WHERE p.id = :id AND p.clientId = :clientId AND (:orgId IS NULL OR p.orgId = :orgId)")
    Optional<PayrollRun> findByIdAndClientIdAndOrgId(@Param("id") UUID id, @Param("clientId") UUID clientId, @Param("orgId") UUID orgId);
}
