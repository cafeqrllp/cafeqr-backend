package com.restaurant.pos.hr.repository;

import com.restaurant.pos.hr.entity.SalarySlip;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface SalarySlipRepository extends JpaRepository<SalarySlip, UUID> {
    
    @Query("SELECT s FROM SalarySlip s WHERE s.clientId = :clientId AND (:orgId IS NULL OR s.orgId = :orgId)")
    List<SalarySlip> findByClientIdAndOrgId(@Param("clientId") UUID clientId, @Param("orgId") UUID orgId);

    @Query("SELECT s FROM SalarySlip s WHERE s.id = :id AND s.clientId = :clientId AND (:orgId IS NULL OR s.orgId = :orgId)")
    Optional<SalarySlip> findByIdAndClientIdAndOrgId(@Param("id") UUID id, @Param("clientId") UUID clientId, @Param("orgId") UUID orgId);
    
    @Query("SELECT s FROM SalarySlip s WHERE s.payrollRun.id = :payrollRunId AND s.clientId = :clientId AND (:orgId IS NULL OR s.orgId = :orgId)")
    List<SalarySlip> findByPayrollRunIdAndClientIdAndOrgId(@Param("payrollRunId") UUID payrollRunId, @Param("clientId") UUID clientId, @Param("orgId") UUID orgId);
}
