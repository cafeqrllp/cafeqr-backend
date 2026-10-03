package com.restaurant.pos.inventory.repository;

import com.restaurant.pos.inventory.domain.StockSnapshot;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import org.springframework.data.jpa.repository.Lock;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface StockSnapshotRepository extends JpaRepository<StockSnapshot, UUID> {
    
    List<StockSnapshot> findByClientIdAndWarehouseId(UUID clientId, UUID warehouseId);
    
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM StockSnapshot s WHERE s.warehouseId = :warehouseId AND s.productId = :productId AND ((:variantId IS NULL AND s.variantId IS NULL) OR s.variantId = :variantId)")
    List<StockSnapshot> findByWarehouseIdAndProductIdAndVariantId(
            @Param("warehouseId") UUID warehouseId, 
            @Param("productId") UUID productId, 
            @Param("variantId") UUID variantId);
    
    List<StockSnapshot> findByWarehouseId(UUID warehouseId);

    List<StockSnapshot> findByClientId(UUID clientId);

    List<StockSnapshot> findByClientIdAndOrgId(UUID clientId, UUID orgId);

    @Query("SELECT s FROM StockSnapshot s WHERE s.clientId = :clientId AND (:orgId IS NULL OR s.orgId = :orgId OR s.orgId IS NULL)")
    List<StockSnapshot> findByClientIdAndOrgIdOrGlobal(@Param("clientId") UUID clientId, @Param("orgId") UUID orgId);
}
