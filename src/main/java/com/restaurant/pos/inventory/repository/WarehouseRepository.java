package com.restaurant.pos.inventory.repository;

import com.restaurant.pos.inventory.domain.Warehouse;
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
}
