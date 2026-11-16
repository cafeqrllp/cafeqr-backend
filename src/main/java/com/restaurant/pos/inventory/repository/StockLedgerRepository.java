package com.restaurant.pos.inventory.repository;

import com.restaurant.pos.inventory.domain.StockLedger;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Repository
public interface StockLedgerRepository extends JpaRepository<StockLedger, UUID> {
    
    List<StockLedger> findByWarehouseIdOrderByTransactionDateDesc(UUID warehouseId);

    Page<StockLedger> findByWarehouseIdOrderByTransactionDateDesc(UUID warehouseId, Pageable pageable);
    
    List<StockLedger> findByWarehouseIdAndProductIdOrderByTransactionDateDesc(UUID warehouseId, UUID productId);
    
    List<StockLedger> findByReferenceId(UUID referenceId);

    List<StockLedger> findByReferenceIdAndProductIdAndTransactionTypeInOrderByTransactionDateDesc(
            UUID referenceId, UUID productId, java.util.Collection<String> transactionTypes);

    List<StockLedger> findByReferenceIdAndTransactionTypeIn(UUID referenceId, java.util.Collection<String> transactionTypes);

    List<StockLedger> findByReferenceIdInAndTransactionTypeIn(java.util.Collection<UUID> referenceIds,
                                                              java.util.Collection<String> transactionTypes);

    List<StockLedger> findByClientIdAndOrgIdAndReferenceId(UUID clientId, UUID orgId, UUID referenceId);

    /** Latest movements of the given types for a product in an org (last purchase price lookup). */
    List<StockLedger> findTop20ByOrgIdAndProductIdAndTransactionTypeInOrderByTransactionDateDesc(
            UUID orgId, UUID productId, java.util.Collection<String> transactionTypes);

    /** Movements of one product in an org within a period, oldest first (product cost report). */
    List<StockLedger> findByOrgIdAndProductIdAndTransactionDateBetweenOrderByTransactionDateAsc(
            UUID orgId, UUID productId, LocalDateTime from, LocalDateTime to);

    List<StockLedger> findByClientIdAndOrgIdAndTransactionDateBetweenOrderByTransactionDateAsc(UUID clientId, UUID orgId, LocalDateTime from, LocalDateTime to);
}
