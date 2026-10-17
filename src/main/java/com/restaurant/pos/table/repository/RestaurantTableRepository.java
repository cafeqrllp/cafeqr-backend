package com.restaurant.pos.table.repository;

import com.restaurant.pos.client.dto.ClientCountProjection;
import com.restaurant.pos.table.domain.RestaurantTable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface RestaurantTableRepository extends JpaRepository<RestaurantTable, UUID> {

    List<RestaurantTable> findByClientIdOrderByDisplayOrderAscTableNumberAsc(UUID clientId);

    @Query("SELECT t FROM RestaurantTable t WHERE t.clientId = :clientId AND t.orgId = :orgId ORDER BY t.displayOrder ASC, t.tableNumber ASC")
    List<RestaurantTable> findByClientIdAndOrgIdOrderByDisplayOrderAscTableNumberAsc(@Param("clientId") UUID clientId, @Param("orgId") UUID orgId);

    Optional<RestaurantTable> findByIdAndClientId(UUID id, UUID clientId);

    @Query("SELECT t FROM RestaurantTable t WHERE t.id = :id AND t.clientId = :clientId AND t.orgId = :orgId")
    Optional<RestaurantTable> findByIdAndClientIdAndOrgId(@Param("id") UUID id, @Param("clientId") UUID clientId, @Param("orgId") UUID orgId);

    List<RestaurantTable> findByClientIdAndIsactiveOrderByDisplayOrderAscTableNumberAsc(UUID clientId, String isactive);

    @Query("SELECT t FROM RestaurantTable t WHERE t.clientId = :clientId AND t.orgId = :orgId AND t.isactive = :isactive ORDER BY t.displayOrder ASC, t.tableNumber ASC")
    List<RestaurantTable> findByClientIdAndOrgIdAndIsactiveOrderByDisplayOrderAscTableNumberAsc(@Param("clientId") UUID clientId, @Param("orgId") UUID orgId, @Param("isactive") String isactive);

    List<RestaurantTable> findByClientIdAndUpdatedAtAfterOrderByDisplayOrderAscTableNumberAsc(UUID clientId, LocalDateTime updatedAfter);

    @Query("SELECT t FROM RestaurantTable t WHERE t.clientId = :clientId AND t.orgId = :orgId AND t.updatedAt > :updatedAfter ORDER BY t.displayOrder ASC, t.tableNumber ASC")
    List<RestaurantTable> findByClientIdAndOrgIdAndUpdatedAtAfterOrderByDisplayOrderAscTableNumberAsc(@Param("clientId") UUID clientId, @Param("orgId") UUID orgId, @Param("updatedAfter") LocalDateTime updatedAfter);

    @Query("SELECT t.clientId AS clientId, COUNT(t.id) AS count FROM RestaurantTable t WHERE t.clientId IS NOT NULL AND (t.isactive IS NULL OR t.isactive = 'Y') GROUP BY t.clientId")
    List<ClientCountProjection> countActiveTablesPerClient();
}
