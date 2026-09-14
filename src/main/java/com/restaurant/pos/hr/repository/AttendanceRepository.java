package com.restaurant.pos.hr.repository;

import com.restaurant.pos.hr.entity.Attendance;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface AttendanceRepository extends JpaRepository<Attendance, UUID> {
    
    @Query("SELECT a FROM Attendance a WHERE a.clientId = :clientId AND (:orgId IS NULL OR a.orgId = :orgId)")
    List<Attendance> findByClientIdAndOrgId(@Param("clientId") UUID clientId, @Param("orgId") UUID orgId);

    @Query("SELECT a FROM Attendance a WHERE a.id = :id AND a.clientId = :clientId AND (:orgId IS NULL OR a.orgId = :orgId)")
    Optional<Attendance> findByIdAndClientIdAndOrgId(@Param("id") UUID id, @Param("clientId") UUID clientId, @Param("orgId") UUID orgId);

    @Query("SELECT a FROM Attendance a WHERE a.employee.id = :employeeId AND a.attendanceDate = :date AND a.clientId = :clientId AND (:orgId IS NULL OR a.orgId = :orgId)")
    Optional<Attendance> findByEmployeeIdAndDateAndClientIdAndOrgId(@Param("employeeId") UUID employeeId, @Param("date") LocalDate date, @Param("clientId") UUID clientId, @Param("orgId") UUID orgId);
    
    @Query("SELECT a FROM Attendance a WHERE a.employee.id = :employeeId AND a.attendanceDate BETWEEN :startDate AND :endDate AND a.clientId = :clientId AND (:orgId IS NULL OR a.orgId = :orgId)")
    List<Attendance> findByEmployeeIdAndDateRangeAndClientIdAndOrgId(@Param("employeeId") UUID employeeId, @Param("startDate") LocalDate startDate, @Param("endDate") LocalDate endDate, @Param("clientId") UUID clientId, @Param("orgId") UUID orgId);

    @Query("SELECT a FROM Attendance a WHERE a.clientId = :clientId AND (:orgId IS NULL OR a.orgId = :orgId) AND a.attendanceDate BETWEEN :startDate AND :endDate ORDER BY a.attendanceDate DESC, a.clockInTime DESC")
    List<Attendance> findAllByDateRangeAndClientIdAndOrgId(@Param("startDate") LocalDate startDate, @Param("endDate") LocalDate endDate, @Param("clientId") UUID clientId, @Param("orgId") UUID orgId);

    @Query("SELECT a FROM Attendance a WHERE a.employee.id = :employeeId AND a.clockOutTime IS NULL AND a.clientId = :clientId AND (:orgId IS NULL OR a.orgId = :orgId)")
    List<Attendance> findActiveAttendanceByEmployeeIdAndClientIdAndOrgId(@Param("employeeId") UUID employeeId, @Param("clientId") UUID clientId, @Param("orgId") UUID orgId);

    @Query("SELECT a FROM Attendance a WHERE a.employee.id = :employeeId AND a.attendanceDate BETWEEN :startDate AND :endDate AND a.status != 'ABSENT' AND a.clientId = :clientId AND (:orgId IS NULL OR a.orgId = :orgId)")
    List<Attendance> findPresentByEmployeeIdAndDateRange(@Param("employeeId") UUID employeeId, @Param("startDate") LocalDate startDate, @Param("endDate") LocalDate endDate, @Param("clientId") UUID clientId, @Param("orgId") UUID orgId);
}
