package com.restaurant.pos.hr.repository;

import com.restaurant.pos.hr.entity.LeaveRequest;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface LeaveRequestRepository extends JpaRepository<LeaveRequest, UUID> {
    
    @Query("SELECT l FROM LeaveRequest l WHERE l.clientId = :clientId AND (:orgId IS NULL OR l.orgId = :orgId)")
    List<LeaveRequest> findByClientIdAndOrgId(@Param("clientId") UUID clientId, @Param("orgId") UUID orgId);

    @Query("SELECT l FROM LeaveRequest l WHERE l.id = :id AND l.clientId = :clientId AND (:orgId IS NULL OR l.orgId = :orgId)")
    Optional<LeaveRequest> findByIdAndClientIdAndOrgId(@Param("id") UUID id, @Param("clientId") UUID clientId, @Param("orgId") UUID orgId);

    @Query("SELECT l FROM LeaveRequest l WHERE l.employee.id = :employeeId AND l.clientId = :clientId AND (:orgId IS NULL OR l.orgId = :orgId)")
    List<LeaveRequest> findByEmployeeIdAndClientIdAndOrgId(@Param("employeeId") UUID employeeId, @Param("clientId") UUID clientId, @Param("orgId") UUID orgId);
    
    @Query("SELECT l FROM LeaveRequest l WHERE l.employee.id = :employeeId AND l.status = 'APPROVED' AND l.startDate <= :endDate AND l.endDate >= :startDate AND l.clientId = :clientId AND (:orgId IS NULL OR l.orgId = :orgId)")
    List<LeaveRequest> findApprovedByEmployeeIdAndDateRange(@Param("employeeId") UUID employeeId, @Param("startDate") LocalDate startDate, @Param("endDate") LocalDate endDate, @Param("clientId") UUID clientId, @Param("orgId") UUID orgId);

    @Query("SELECT l FROM LeaveRequest l WHERE l.employee.id = :employeeId AND l.status = 'APPROVED' AND l.startDate <= :date AND l.endDate >= :date AND l.clientId = :clientId AND (:orgId IS NULL OR l.orgId = :orgId)")
    List<LeaveRequest> findApprovedByEmployeeIdAndDate(@Param("employeeId") UUID employeeId, @Param("date") LocalDate date, @Param("clientId") UUID clientId, @Param("orgId") UUID orgId);
}
