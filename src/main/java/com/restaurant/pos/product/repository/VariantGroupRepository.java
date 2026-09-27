package com.restaurant.pos.product.repository;

import com.restaurant.pos.product.domain.VariantGroup;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Repository
public interface VariantGroupRepository extends JpaRepository<VariantGroup, UUID> {
    UUID NIL_ORG_ID = new UUID(0L, 0L);

    @EntityGraph(attributePaths = {"options"})
    @Query("SELECT DISTINCT v FROM VariantGroup v WHERE (v.clientId = :clientId OR v.clientId IS NULL) AND (:orgId IS NULL OR v.orgId = :orgId OR v.orgId IS NULL OR v.orgId = :nilOrgId)")
    List<VariantGroup> findByClientIdAndOrgIdOrGlobalInternal(@Param("clientId") UUID clientId, @Param("orgId") UUID orgId, @Param("nilOrgId") UUID nilOrgId);

    default List<VariantGroup> findByClientIdAndOrgIdOrGlobal(UUID clientId, UUID orgId) {
        return findByClientIdAndOrgIdOrGlobalInternal(clientId, orgId, NIL_ORG_ID);
    }

    @EntityGraph(attributePaths = {"options"})
    @Query("SELECT DISTINCT v FROM VariantGroup v WHERE (v.clientId = :clientId OR v.clientId IS NULL) AND (:orgId IS NULL OR v.orgId = :orgId OR v.orgId IS NULL OR v.orgId = :nilOrgId) AND v.isActive = true")
    List<VariantGroup> findByClientIdAndOrgIdOrGlobalAndIsActiveTrueInternal(@Param("clientId") UUID clientId, @Param("orgId") UUID orgId, @Param("nilOrgId") UUID nilOrgId);

    default List<VariantGroup> findByClientIdAndOrgIdOrGlobalAndIsActiveTrue(UUID clientId, UUID orgId) {
        return findByClientIdAndOrgIdOrGlobalAndIsActiveTrueInternal(clientId, orgId, NIL_ORG_ID);
    }

    @EntityGraph(attributePaths = {"options"})
    @Query("SELECT DISTINCT v FROM VariantGroup v WHERE (v.clientId = :clientId OR v.clientId IS NULL) AND (:orgId IS NULL OR v.orgId = :orgId OR v.orgId IS NULL OR v.orgId = :nilOrgId) AND v.updatedAt >= :updatedAfter")
    List<VariantGroup> findChangedByClientIdAndOrgIdOrGlobalInternal(@Param("clientId") UUID clientId, @Param("orgId") UUID orgId, @Param("nilOrgId") UUID nilOrgId, @Param("updatedAfter") LocalDateTime updatedAfter);

    default List<VariantGroup> findChangedByClientIdAndOrgIdOrGlobal(UUID clientId, UUID orgId, LocalDateTime updatedAfter) {
        return findChangedByClientIdAndOrgIdOrGlobalInternal(clientId, orgId, NIL_ORG_ID, updatedAfter);
    }
}
