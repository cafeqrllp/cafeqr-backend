package com.restaurant.pos.product.repository;

import com.restaurant.pos.product.domain.Category;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface CategoryRepository extends JpaRepository<Category, UUID> {
    @Query("SELECT c FROM Category c WHERE (c.clientId = :clientId OR c.clientId IS NULL) AND (c.orgId = :orgId OR c.orgId IS NULL) ORDER BY LOWER(c.name) ASC")
    List<Category> findByClientIdAndOrgIdOrGlobal(UUID clientId, UUID orgId);

    @Query("SELECT c FROM Category c WHERE (c.clientId = :clientId OR c.clientId IS NULL) AND (c.orgId = :orgId OR c.orgId IS NULL) AND c.isActive = true ORDER BY LOWER(c.name) ASC")
    List<Category> findByClientIdAndOrgIdOrGlobalAndIsActiveTrue(UUID clientId, UUID orgId);

    @Query("SELECT c FROM Category c WHERE (c.clientId = :clientId OR c.clientId IS NULL) AND (c.orgId = :orgId OR c.orgId IS NULL) AND c.updatedAt >= :updatedAfter ORDER BY LOWER(c.name) ASC")
    List<Category> findChangedByClientIdAndOrgIdOrGlobal(UUID clientId, UUID orgId, LocalDateTime updatedAfter);
    
    @Query("SELECT c FROM Category c WHERE c.id = :id AND (c.clientId = :clientId OR c.clientId IS NULL) AND (c.orgId = :orgId OR c.orgId IS NULL)")
    Optional<Category> findByIdAndClientIdAndOrgIdOrGlobal(UUID id, UUID clientId, UUID orgId);

    @Query("SELECT c FROM Category c WHERE LOWER(c.name) = LOWER(:name) AND (c.clientId = :clientId OR c.clientId IS NULL) AND (c.orgId = :orgId OR c.orgId IS NULL)")
    Optional<Category> findByNameAndClientIdAndOrgIdOrGlobal(String name, UUID clientId, UUID orgId);
}
