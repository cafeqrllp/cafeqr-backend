package com.restaurant.pos.costing.repository;

import com.restaurant.pos.costing.domain.ProductCost;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ProductCostRepository extends JpaRepository<ProductCost, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT c FROM ProductCost c WHERE c.orgId = :orgId AND c.productId = :productId "
            + "AND ((:variantId IS NULL AND c.variantId IS NULL) OR c.variantId = :variantId)")
    Optional<ProductCost> findForUpdate(@Param("orgId") UUID orgId,
                                        @Param("productId") UUID productId,
                                        @Param("variantId") UUID variantId);

    @Query("SELECT c FROM ProductCost c WHERE c.orgId = :orgId AND c.productId = :productId "
            + "AND ((:variantId IS NULL AND c.variantId IS NULL) OR c.variantId = :variantId)")
    Optional<ProductCost> findCurrent(@Param("orgId") UUID orgId,
                                      @Param("productId") UUID productId,
                                      @Param("variantId") UUID variantId);

    List<ProductCost> findByOrgIdAndProductIdIn(UUID orgId, Collection<UUID> productIds);

    List<ProductCost> findByClientIdAndProductIdIn(UUID clientId, Collection<UUID> productIds);
}
