package com.restaurant.pos.loyalty.repository;

import com.restaurant.pos.loyalty.domain.LoyaltyTransaction;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface LoyaltyTransactionRepository extends JpaRepository<LoyaltyTransaction, UUID> {

    Page<LoyaltyTransaction> findByCustomerIdAndClientIdOrderByCreatedAtDesc(
            UUID customerId, UUID clientId, Pageable pageable);

    Page<LoyaltyTransaction> findByCustomerIdAndClientIdAndOrgIdOrderByCreatedAtDesc(
            UUID customerId, UUID clientId, UUID orgId, Pageable pageable);

    /** Finds all ledger entries for an order (used to create reversals). */
    List<LoyaltyTransaction> findByOrderIdAndClientId(UUID orderId, UUID clientId);

    Optional<LoyaltyTransaction> findFirstByOrderIdAndClientIdOrderByCreatedAtDesc(UUID orderId, UUID clientId);
}
