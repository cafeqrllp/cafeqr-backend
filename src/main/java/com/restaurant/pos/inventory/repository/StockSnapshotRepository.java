package com.restaurant.pos.inventory.repository;

import com.restaurant.pos.inventory.domain.StockSnapshot;
import org.springframework.data.jpa.repository.JpaRepository;
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
    Optional<StockSnapshot> findByWarehouseIdAndProductIdAndVariantId(UUID warehouseId, UUID productId, UUID variantId);
    
    List<StockSnapshot> findByWarehouseId(UUID warehouseId);
}
