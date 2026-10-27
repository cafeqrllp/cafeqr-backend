package com.restaurant.pos.waste.repository;

import com.restaurant.pos.waste.domain.WasteLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface WasteLogRepository extends JpaRepository<WasteLog, UUID> {
    Optional<WasteLog> findByIdAndClientId(UUID id, UUID clientId);
    List<WasteLog> findByClientIdOrderByWasteDateDesc(UUID clientId);
    List<WasteLog> findByClientIdAndWasteDateBetweenOrderByWasteDateDesc(UUID clientId, LocalDateTime start, LocalDateTime end);
    Page<WasteLog> findByClientIdOrderByWasteDateDesc(UUID clientId, Pageable pageable);
    Page<WasteLog> findByClientIdAndWasteDateBetweenOrderByWasteDateDesc(UUID clientId, LocalDateTime start, LocalDateTime end, Pageable pageable);
    long countByClientId(UUID clientId);

    @Query("SELECT COALESCE(SUM(w.totalCost), 0) FROM WasteLog w WHERE w.clientId = :clientId AND w.wasteDate BETWEEN :start AND :end")
    BigDecimal sumTotalCostByClientIdAndDateRange(@Param("clientId") UUID clientId, @Param("start") LocalDateTime start, @Param("end") LocalDateTime end);

    @Query("SELECT w.wasteReason, COUNT(w), COALESCE(SUM(w.totalCost), 0) FROM WasteLog w WHERE w.clientId = :clientId AND w.wasteDate BETWEEN :start AND :end GROUP BY w.wasteReason ORDER BY SUM(w.totalCost) DESC")
    List<Object[]> getWasteBreakdown(@Param("clientId") UUID clientId, @Param("start") LocalDateTime start, @Param("end") LocalDateTime end);
}