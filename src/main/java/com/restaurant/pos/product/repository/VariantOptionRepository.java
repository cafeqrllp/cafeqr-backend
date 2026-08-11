package com.restaurant.pos.product.repository;

import com.restaurant.pos.product.domain.VariantOption;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface VariantOptionRepository extends JpaRepository<VariantOption, UUID> {
    List<VariantOption> findByGroup_IdOrderByCreatedAtAsc(UUID groupId);

    @Query("SELECT v FROM VariantOption v WHERE (v.clientId = :clientId OR v.clientId IS NULL) AND (v.orgId = :orgId OR v.orgId IS NULL) AND v.isActive = true")
    List<VariantOption> findByClientIdAndOrgIdOrGlobalAndIsActiveTrue(UUID clientId, UUID orgId);
}
