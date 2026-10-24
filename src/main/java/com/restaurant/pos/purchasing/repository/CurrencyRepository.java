package com.restaurant.pos.purchasing.repository;

import com.restaurant.pos.purchasing.domain.Currency;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface CurrencyRepository extends JpaRepository<Currency, UUID> {
    List<Currency> findByClientIdOrderByCodeAsc(UUID clientId);
    List<Currency> findByClientIdAndOrgIdOrderByCodeAsc(UUID clientId, UUID orgId);
    Optional<Currency> findByIdAndClientId(UUID id, UUID clientId);
    Optional<Currency> findByIdAndClientIdAndOrgId(UUID id, UUID clientId, UUID orgId);
    List<Currency> findByClientIdAndIsDefaultTrue(UUID clientId);
    List<Currency> findByClientIdAndOrgIdAndIsDefaultTrue(UUID clientId, UUID orgId);
    boolean existsByClientIdAndOrgId(UUID clientId, UUID orgId);
}
