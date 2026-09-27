package com.restaurant.pos.product.repository;

import com.restaurant.pos.product.domain.Uom;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface UomRepository extends JpaRepository<Uom, UUID> {
    UUID NIL_ORG_ID = new UUID(0L, 0L);

    @Query("SELECT u FROM Uom u WHERE (u.clientId = :clientId OR u.clientId IS NULL) AND (:orgId IS NULL OR u.orgId = :orgId OR u.orgId IS NULL OR u.orgId = :nilOrgId)")
    List<Uom> findByClientIdAndOrgIdOrGlobalInternal(@Param("clientId") UUID clientId, @Param("orgId") UUID orgId, @Param("nilOrgId") UUID nilOrgId);

    default List<Uom> findByClientIdAndOrgIdOrGlobal(UUID clientId, UUID orgId) {
        return findByClientIdAndOrgIdOrGlobalInternal(clientId, orgId, NIL_ORG_ID);
    }

    @Query("SELECT u FROM Uom u WHERE (u.clientId = :clientId OR u.clientId IS NULL) AND (:orgId IS NULL OR u.orgId = :orgId OR u.orgId IS NULL OR u.orgId = :nilOrgId) AND u.isActive = true")
    List<Uom> findByClientIdAndOrgIdOrGlobalAndIsActiveTrueInternal(@Param("clientId") UUID clientId, @Param("orgId") UUID orgId, @Param("nilOrgId") UUID nilOrgId);

    default List<Uom> findByClientIdAndOrgIdOrGlobalAndIsActiveTrue(UUID clientId, UUID orgId) {
        return findByClientIdAndOrgIdOrGlobalAndIsActiveTrueInternal(clientId, orgId, NIL_ORG_ID);
    }

    @Query("SELECT u FROM Uom u WHERE (u.clientId = :clientId OR u.clientId IS NULL) AND (:orgId IS NULL OR u.orgId = :orgId OR u.orgId IS NULL OR u.orgId = :nilOrgId) AND u.updatedAt >= :updatedAfter")
    List<Uom> findChangedByClientIdAndOrgIdOrGlobalInternal(@Param("clientId") UUID clientId, @Param("orgId") UUID orgId, @Param("nilOrgId") UUID nilOrgId, @Param("updatedAfter") LocalDateTime updatedAfter);

    default List<Uom> findChangedByClientIdAndOrgIdOrGlobal(UUID clientId, UUID orgId, LocalDateTime updatedAfter) {
        return findChangedByClientIdAndOrgIdOrGlobalInternal(clientId, orgId, NIL_ORG_ID, updatedAfter);
    }

    @Query("SELECT u FROM Uom u WHERE LOWER(u.name) = LOWER(:name) AND (u.clientId = :clientId OR u.clientId IS NULL) AND (:orgId IS NULL OR u.orgId = :orgId OR u.orgId IS NULL OR u.orgId = :nilOrgId)")
    Optional<Uom> findByNameAndClientIdAndOrgIdOrGlobalInternal(@Param("name") String name, @Param("clientId") UUID clientId, @Param("orgId") UUID orgId, @Param("nilOrgId") UUID nilOrgId);

    default Optional<Uom> findByNameAndClientIdAndOrgIdOrGlobal(String name, UUID clientId, UUID orgId) {
        return findByNameAndClientIdAndOrgIdOrGlobalInternal(name, clientId, orgId, NIL_ORG_ID);
    }
}
