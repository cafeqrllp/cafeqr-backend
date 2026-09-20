package com.restaurant.pos.hr.repository;

import com.restaurant.pos.hr.entity.SalaryComponent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface SalaryComponentRepository extends JpaRepository<SalaryComponent, UUID> {
    
    @Query("SELECT s FROM SalaryComponent s WHERE (s.clientId = :clientId OR s.clientId IS NULL) AND (:orgId IS NULL OR s.orgId = :orgId OR s.orgId IS NULL)")
    List<SalaryComponent> findByClientIdAndOrgIdOrGlobal(@Param("clientId") UUID clientId, @Param("orgId") UUID orgId);

    @Query("SELECT s FROM SalaryComponent s WHERE s.id = :id AND (s.clientId = :clientId OR s.clientId IS NULL) AND (:orgId IS NULL OR s.orgId = :orgId OR s.orgId IS NULL)")
    Optional<SalaryComponent> findByIdAndClientIdAndOrgIdOrGlobal(@Param("id") UUID id, @Param("clientId") UUID clientId, @Param("orgId") UUID orgId);
    
    @Query("SELECT s FROM SalaryComponent s WHERE s.isActive = true AND (s.clientId = :clientId OR s.clientId IS NULL) AND (:orgId IS NULL OR s.orgId = :orgId OR s.orgId IS NULL)")
    List<SalaryComponent> findActiveComponents(@Param("clientId") UUID clientId, @Param("orgId") UUID orgId);

    @Query("SELECT s FROM SalaryComponent s WHERE LOWER(TRIM(s.name)) = LOWER(TRIM(:name)) AND (s.clientId = :clientId OR s.clientId IS NULL) AND (:orgId IS NULL OR s.orgId = :orgId OR s.orgId IS NULL)")
    List<SalaryComponent> findByNameIgnoreCaseAndClientIdAndOrgIdOrGlobal(@Param("name") String name, @Param("clientId") UUID clientId, @Param("orgId") UUID orgId);
}
