package com.restaurant.pos.warehouse.repository;

import com.restaurant.pos.warehouse.domain.Warehouse;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface WarehouseRepository extends JpaRepository<Warehouse, UUID> {
    
    List<Warehouse> findByClientIdOrderByCreatedAtDesc(UUID clientId);
    
    List<Warehouse> findByClientIdAndOrgIdOrderByCreatedAtDesc(UUID clientId, UUID orgId);
    
    Optional<Warehouse> findByIdAndClientId(UUID id, UUID clientId);
    
    Optional<Warehouse> findByIdAndClientIdAndOrgId(UUID id, UUID clientId, UUID orgId);

    @Query("SELECT w FROM Warehouse w WHERE w.clientId = :clientId AND (w.orgId = :orgId OR w.orgId IS NULL) ORDER BY w.createdAt DESC")
    List<Warehouse> findByClientIdAndOrgIdOrGlobalOrderByCreatedAtDesc(@Param("clientId") UUID clientId, @Param("orgId") UUID orgId);

    @Query("SELECT w FROM Warehouse w WHERE w.id = :id AND w.clientId = :clientId AND (w.orgId = :orgId OR w.orgId IS NULL)")
    Optional<Warehouse> findByIdAndClientIdAndOrgIdOrGlobal(@Param("id") UUID id, @Param("clientId") UUID clientId, @Param("orgId") UUID orgId);

    /** Find the default warehouse for a specific org (or fall back to a global default for the client) */
    @Query("SELECT w FROM Warehouse w WHERE w.clientId = :clientId AND (w.orgId = :orgId OR w.orgId IS NULL) AND w.isDefault = true ORDER BY CASE WHEN w.orgId = :orgId THEN 0 ELSE 1 END, w.createdAt ASC")
    List<Warehouse> findDefaultWarehousesForOrg(@Param("clientId") UUID clientId, @Param("orgId") UUID orgId);

    /** Find any default warehouse for a client (when orgId is unknown) */
    Optional<Warehouse> findFirstByClientIdAndIsDefaultTrue(UUID clientId);

    @org.springframework.data.jpa.repository.Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE Warehouse w SET w.isDefault = false WHERE w.clientId = :clientId AND ((:orgId IS NOT NULL AND w.orgId = :orgId) OR (:orgId IS NULL AND w.orgId IS NULL)) AND (:excludeId IS NULL OR w.id <> :excludeId)")
    void unsetOtherDefaultsForOrg(@Param("clientId") UUID clientId, @Param("orgId") UUID orgId, @Param("excludeId") UUID excludeId);
}
