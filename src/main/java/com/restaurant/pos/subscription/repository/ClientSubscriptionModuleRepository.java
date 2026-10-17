package com.restaurant.pos.subscription.repository;

import com.restaurant.pos.subscription.domain.ClientSubscriptionModule;
import com.restaurant.pos.subscription.domain.ModuleName;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ClientSubscriptionModuleRepository extends JpaRepository<ClientSubscriptionModule, UUID> {
    List<ClientSubscriptionModule> findByClientId(UUID clientId);
    List<ClientSubscriptionModule> findByOrgId(UUID orgId);
    Optional<ClientSubscriptionModule> findByClientIdAndModuleName(UUID clientId, ModuleName moduleName);
    Optional<ClientSubscriptionModule> findByClientIdAndOrgIdAndModuleName(UUID clientId, UUID orgId, ModuleName moduleName);
}
