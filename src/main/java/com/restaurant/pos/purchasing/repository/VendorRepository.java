package com.restaurant.pos.purchasing.repository;

import com.restaurant.pos.purchasing.domain.Vendor;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface VendorRepository extends JpaRepository<Vendor, UUID> {
    List<Vendor> findByClientIdOrderByNameAsc(UUID clientId);
    List<Vendor> findByClientIdAndOrgIdOrderByNameAsc(UUID clientId, UUID orgId);
    Optional<Vendor> findByIdAndClientId(UUID id, UUID clientId);
    Optional<Vendor> findByIdAndClientIdAndOrgId(UUID id, UUID clientId, UUID orgId);

    @Query("SELECT v FROM Vendor v WHERE v.clientId = :clientId AND (v.orgId = :orgId OR v.orgId IS NULL) ORDER BY v.name ASC")
    List<Vendor> findByClientIdAndOrgIdOrGlobalOrderByNameAsc(@Param("clientId") UUID clientId, @Param("orgId") UUID orgId);

    @Query("SELECT v FROM Vendor v WHERE v.id = :id AND v.clientId = :clientId AND (v.orgId = :orgId OR v.orgId IS NULL)")
    Optional<Vendor> findByIdAndClientIdAndOrgIdOrGlobal(@Param("id") UUID id, @Param("clientId") UUID clientId, @Param("orgId") UUID orgId);
}
