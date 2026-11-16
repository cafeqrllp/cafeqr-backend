package com.restaurant.pos.cashdrawer.repository;

import com.restaurant.pos.cashdrawer.domain.CashDrawerEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface CashDrawerEventRepository extends JpaRepository<CashDrawerEvent, UUID>, JpaSpecificationExecutor<CashDrawerEvent> {

    Optional<CashDrawerEvent> findByClientIdAndClientEventId(UUID clientId, String clientEventId);
}
