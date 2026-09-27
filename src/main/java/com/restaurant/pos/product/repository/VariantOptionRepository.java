package com.restaurant.pos.product.repository;

import com.restaurant.pos.product.domain.VariantOption;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface VariantOptionRepository extends JpaRepository<VariantOption, UUID> {
    UUID NIL_ORG_ID = new UUID(0L, 0L);

    List<VariantOption> findByGroup_IdOrderByCreatedAtAsc(UUID groupId);

    @Query("SELECT v FROM VariantOption v WHERE (v.clientId = :clientId OR v.clientId IS NULL) AND (:orgId IS NULL OR v.orgId = :orgId OR v.orgId IS NULL OR v.orgId = :nilOrgId) AND v.isActive = true")
    List<VariantOption> findByClientIdAndOrgIdOrGlobalAndIsActiveTrueInternal(@Param("clientId") UUID clientId, @Param("orgId") UUID orgId, @Param("nilOrgId") UUID nilOrgId);

    default List<VariantOption> findByClientIdAndOrgIdOrGlobalAndIsActiveTrue(UUID clientId, UUID orgId) {
        return findByClientIdAndOrgIdOrGlobalAndIsActiveTrueInternal(clientId, orgId, NIL_ORG_ID);
    }
}
