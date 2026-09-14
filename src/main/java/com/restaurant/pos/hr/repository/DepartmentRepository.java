package com.restaurant.pos.hr.repository;

import com.restaurant.pos.hr.entity.Department;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface DepartmentRepository extends JpaRepository<Department, UUID> {
    
    @Query("SELECT d FROM Department d WHERE d.clientId = :clientId AND (:orgId IS NULL OR d.orgId = :orgId)")
    List<Department> findByClientIdAndOrgId(@Param("clientId") UUID clientId, @Param("orgId") UUID orgId);

    @Query("SELECT d FROM Department d WHERE d.id = :id AND d.clientId = :clientId AND (:orgId IS NULL OR d.orgId = :orgId)")
    Optional<Department> findByIdAndClientIdAndOrgId(@Param("id") UUID id, @Param("clientId") UUID clientId, @Param("orgId") UUID orgId);
}
