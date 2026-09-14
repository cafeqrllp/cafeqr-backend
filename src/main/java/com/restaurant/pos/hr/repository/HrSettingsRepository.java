package com.restaurant.pos.hr.repository;

import com.restaurant.pos.hr.entity.HrSettings;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface HrSettingsRepository extends JpaRepository<HrSettings, UUID> {

    @Query("SELECT s FROM HrSettings s WHERE s.clientId = :clientId AND (:orgId IS NULL OR s.orgId = :orgId OR s.orgId IS NULL)")
    Optional<HrSettings> findByClientIdAndOrgId(@Param("clientId") UUID clientId, @Param("orgId") UUID orgId);
}
