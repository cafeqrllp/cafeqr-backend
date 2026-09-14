package com.restaurant.pos.hr.repository;

import com.restaurant.pos.hr.entity.Designation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface DesignationRepository extends JpaRepository<Designation, UUID> {
    
    @Query("SELECT d FROM Designation d WHERE d.clientId = :clientId AND (:orgId IS NULL OR d.orgId = :orgId)")
    List<Designation> findByClientIdAndOrgId(@Param("clientId") UUID clientId, @Param("orgId") UUID orgId);

    @Query("SELECT d FROM Designation d WHERE d.id = :id AND d.clientId = :clientId AND (:orgId IS NULL OR d.orgId = :orgId)")
    Optional<Designation> findByIdAndClientIdAndOrgId(@Param("id") UUID id, @Param("clientId") UUID clientId, @Param("orgId") UUID orgId);
}
