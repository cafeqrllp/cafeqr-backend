package com.restaurant.pos.hr.repository;

import com.restaurant.pos.hr.entity.SalaryAdvance;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface SalaryAdvanceRepository extends JpaRepository<SalaryAdvance, UUID> {
    
    @Query("SELECT s FROM SalaryAdvance s WHERE s.clientId = :clientId AND (:orgId IS NULL OR s.orgId = :orgId)")
    List<SalaryAdvance> findByClientIdAndOrgId(@Param("clientId") UUID clientId, @Param("orgId") UUID orgId);

    @Query("SELECT s FROM SalaryAdvance s WHERE s.id = :id AND s.clientId = :clientId AND (:orgId IS NULL OR s.orgId = :orgId)")
    Optional<SalaryAdvance> findByIdAndClientIdAndOrgId(@Param("id") UUID id, @Param("clientId") UUID clientId, @Param("orgId") UUID orgId);

    @Query("SELECT s FROM SalaryAdvance s WHERE s.employee.id = :employeeId AND s.clientId = :clientId AND (:orgId IS NULL OR s.orgId = :orgId)")
    List<SalaryAdvance> findByEmployeeIdAndClientIdAndOrgId(@Param("employeeId") UUID employeeId, @Param("clientId") UUID clientId, @Param("orgId") UUID orgId);
    
    @Query("SELECT s FROM SalaryAdvance s WHERE s.employee.id = :employeeId AND s.status = 'APPROVED' AND s.remainingBalance > 0 AND s.clientId = :clientId AND (:orgId IS NULL OR s.orgId = :orgId)")
    List<SalaryAdvance> findActiveAdvancesByEmployeeId(@Param("employeeId") UUID employeeId, @Param("clientId") UUID clientId, @Param("orgId") UUID orgId);
}
