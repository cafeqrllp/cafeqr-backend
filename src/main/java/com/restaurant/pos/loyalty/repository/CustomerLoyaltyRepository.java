package com.restaurant.pos.loyalty.repository;

import com.restaurant.pos.loyalty.domain.CustomerLoyalty;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface CustomerLoyaltyRepository extends JpaRepository<CustomerLoyalty, UUID> {

    Optional<CustomerLoyalty> findByCustomerIdAndClientId(UUID customerId, UUID clientId);

    Optional<CustomerLoyalty> findByCustomerIdAndClientIdAndOrgId(UUID customerId, UUID clientId, UUID orgId);

    Optional<CustomerLoyalty> findByCustomerIdAndClientIdAndOrgIdIsNull(UUID customerId, UUID clientId);

    /**
     * Acquires a pessimistic write lock on the customer's loyalty account.
     * Use this during point redemption to prevent concurrent over-deduction.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT cl FROM CustomerLoyalty cl WHERE cl.customerId = :customerId AND cl.clientId = :clientId AND cl.orgId = :orgId")
    Optional<CustomerLoyalty> findByCustomerIdAndClientIdAndOrgIdWithLock(
            @Param("customerId") UUID customerId,
            @Param("clientId") UUID clientId,
            @Param("orgId") UUID orgId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT cl FROM CustomerLoyalty cl WHERE cl.customerId = :customerId AND cl.clientId = :clientId AND cl.orgId IS NULL")
    Optional<CustomerLoyalty> findByCustomerIdAndClientIdWithLock(
            @Param("customerId") UUID customerId,
            @Param("clientId") UUID clientId);

    /**
     * All accounts for a customer within a client, oldest first. Points are shared across
     * branches, so callers use the oldest account; this avoids the multi-row failure
     * {@link #findByCustomerIdAndClientId} would hit if legacy per-branch accounts exist.
     */
    List<CustomerLoyalty> findByCustomerIdAndClientIdOrderByCreatedAtAsc(UUID customerId, UUID clientId);

    /** Re-reads an account with a pessimistic write lock (serialises earn/redeem/reverse per customer). */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT cl FROM CustomerLoyalty cl WHERE cl.id = :id")
    Optional<CustomerLoyalty> findByIdForUpdate(@Param("id") UUID id);

    List<CustomerLoyalty> findByClientIdAndOrgIdOrderByCurrentPointsDesc(UUID clientId, UUID orgId);

    List<CustomerLoyalty> findByClientIdAndOrgIdIsNullOrderByCurrentPointsDesc(UUID clientId);
}
