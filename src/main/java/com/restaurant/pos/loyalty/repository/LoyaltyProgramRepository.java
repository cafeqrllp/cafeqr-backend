package com.restaurant.pos.loyalty.repository;

import com.restaurant.pos.loyalty.domain.LoyaltyProgram;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface LoyaltyProgramRepository extends JpaRepository<LoyaltyProgram, UUID> {

    List<LoyaltyProgram> findByClientIdAndOrgIdOrderByPriorityDescNameAsc(UUID clientId, UUID orgId);

    List<LoyaltyProgram> findByClientIdAndOrgIdIsNullOrderByPriorityDescNameAsc(UUID clientId);

    Optional<LoyaltyProgram> findByClientIdAndIsDefaultTrueAndOrgIdIsNull(UUID clientId);

    Optional<LoyaltyProgram> findByClientIdAndOrgIdAndIsDefaultTrue(UUID clientId, UUID orgId);

    /**
     * Clears the default flag for all programmes in the given (client, org) scope,
     * so a new default can be set safely without violating the partial unique index.
     */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE LoyaltyProgram p SET p.isDefault = false WHERE p.clientId = :clientId AND p.orgId = :orgId")
    void clearDefaultForOrg(@Param("clientId") UUID clientId, @Param("orgId") UUID orgId);

    @Modifying(clearAutomatically = true)
    @Query("UPDATE LoyaltyProgram p SET p.isDefault = false WHERE p.clientId = :clientId AND p.orgId IS NULL")
    void clearDefaultForClient(@Param("clientId") UUID clientId);

    List<LoyaltyProgram> findByClientIdAndOrgIdAndIsActiveTrueOrderByPriorityDesc(UUID clientId, UUID orgId);

    List<LoyaltyProgram> findByClientIdAndOrgIdIsNullAndIsActiveTrueOrderByPriorityDesc(UUID clientId);
}
