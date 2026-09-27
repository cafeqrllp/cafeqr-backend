package com.restaurant.pos.accounting.repository;

import com.restaurant.pos.accounting.domain.JournalEntry;
import com.restaurant.pos.accounting.domain.JournalStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public interface JournalEntryRepository extends JpaRepository<JournalEntry, UUID>, JpaSpecificationExecutor<JournalEntry> {

    interface AccountMovementProjection {
        UUID getAccountId();
        BigDecimal getDebit();
        BigDecimal getCredit();
    }

    interface PostedSourceProjection {
        String getSourceType();
        UUID getSourceId();
    }

    Optional<JournalEntry> findByIdAndClientId(UUID id, UUID clientId);

    Optional<JournalEntry> findByIdAndClientIdAndOrgId(UUID id, UUID clientId, UUID orgId);

    Optional<JournalEntry> findByClientIdAndOrgIdAndSourceTypeAndSourceId(UUID clientId, UUID orgId, String sourceType, UUID sourceId);

    // Org-agnostic versions
    Optional<JournalEntry> findFirstByClientIdAndSourceTypeAndSourceId(UUID clientId, String sourceType, UUID sourceId);

    boolean existsByClientIdAndOrgIdAndSourceTypeAndSourceId(UUID clientId, UUID orgId, String sourceType, UUID sourceId);

    boolean existsByClientIdAndSourceTypeAndSourceId(UUID clientId, String sourceType, UUID sourceId);

    @Query("""
            SELECT DISTINCT j FROM JournalEntry j
            LEFT JOIN j.lines l
            WHERE j.clientId = :clientId
              AND (:orgId IS NULL OR j.orgId = :orgId)
              AND j.sourceType = :sourceType
              AND j.sourceId = :sourceId
              AND j.status = :status
              AND COALESCE(UPPER(j.isactive), 'Y') <> 'N'
            ORDER BY j.entryDate DESC
            """)
    List<JournalEntry> findActiveBySource(
            @Param("clientId") UUID clientId,
            @Param("orgId") UUID orgId,
            @Param("sourceType") String sourceType,
            @Param("sourceId") UUID sourceId,
            @Param("status") JournalStatus status);

    @EntityGraph(attributePaths = {"lines"})
    @Query("""
            SELECT j FROM JournalEntry j
            WHERE j.clientId = :clientId
              AND (:orgId IS NULL OR j.orgId = :orgId)
              AND j.sourceType = :sourceType
              AND j.sourceId = :sourceId
              AND j.status = :status
              AND COALESCE(UPPER(j.isactive), 'Y') <> 'N'
            ORDER BY j.entryDate DESC
            """)
    List<JournalEntry> findActiveBySourceWithLines(
            @Param("clientId") UUID clientId,
            @Param("orgId") UUID orgId,
            @Param("sourceType") String sourceType,
            @Param("sourceId") UUID sourceId,
            @Param("status") JournalStatus status);

    @Query("""
            SELECT l.accountId AS accountId,
                   COALESCE(SUM(l.debit), 0) AS debit,
                   COALESCE(SUM(l.credit), 0) AS credit
            FROM JournalEntry j
            JOIN j.lines l
            WHERE j.clientId = :clientId
              AND (:orgId IS NULL OR j.orgId = :orgId)
              AND (:terminalId IS NULL OR j.terminalId = :terminalId)
              AND (CAST(:from AS timestamp) IS NULL OR j.entryDate >= :from)
              AND (CAST(:to AS timestamp) IS NULL OR j.entryDate <= :to)
              AND j.status = :status
              AND COALESCE(UPPER(j.isactive), 'Y') <> 'N'
            GROUP BY l.accountId
            """)
    List<AccountMovementProjection> sumLineMovements(
            @Param("clientId") UUID clientId,
            @Param("orgId") UUID orgId,
            @Param("terminalId") UUID terminalId,
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to,
            @Param("status") JournalStatus status);

    @Query("""
            SELECT COUNT(j)
            FROM JournalEntry j
            WHERE j.clientId = :clientId
              AND (:orgId IS NULL OR j.orgId = :orgId)
              AND (:terminalId IS NULL OR j.terminalId = :terminalId)
              AND (CAST(:from AS timestamp) IS NULL OR j.entryDate >= :from)
              AND (CAST(:to AS timestamp) IS NULL OR j.entryDate <= :to)
              AND j.status = :status
              AND COALESCE(UPPER(j.isactive), 'Y') <> 'N'
            """)
    long countPostedActive(
            @Param("clientId") UUID clientId,
            @Param("orgId") UUID orgId,
            @Param("terminalId") UUID terminalId,
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to,
            @Param("status") JournalStatus status);

    default List<PostedSourceProjection> findPostedSources(
            UUID clientId,
            UUID orgId,
            UUID terminalId,
            LocalDateTime from,
            LocalDateTime to,
            JournalStatus status,
            Set<String> sourceTypes) {
        List<JournalEntry> entries = findAll((root, query, cb) -> {
            var predicates = new java.util.ArrayList<jakarta.persistence.criteria.Predicate>();
            predicates.add(cb.equal(root.get("clientId"), clientId));
            if (orgId != null) {
                predicates.add(cb.equal(root.get("orgId"), orgId));
            }
            if (terminalId != null) {
                predicates.add(cb.equal(root.get("terminalId"), terminalId));
            }
            if (from != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("entryDate"), from));
            }
            if (to != null) {
                predicates.add(cb.lessThanOrEqualTo(root.get("entryDate"), to));
            }
            if (status != null) {
                predicates.add(cb.equal(root.get("status"), status));
            }
            predicates.add(cb.notEqual(cb.upper(cb.coalesce(root.get("isactive"), "Y")), "N"));
            predicates.add(cb.isNotNull(root.get("sourceId")));
            if (sourceTypes != null && !sourceTypes.isEmpty()) {
                predicates.add(root.get("sourceType").in(sourceTypes));
            }
            return cb.and(predicates.toArray(new jakarta.persistence.criteria.Predicate[0]));
        });
        return entries.stream()
                .map(e -> (PostedSourceProjection) new PostedSourceProjection() {
                    @Override
                    public String getSourceType() { return e.getSourceType(); }
                    @Override
                    public UUID getSourceId() { return e.getSourceId(); }
                })
                .toList();
    }

    @Modifying
    @Query(value = "DELETE FROM journal_lines WHERE journal_entry_id IN (SELECT id FROM journal_entries WHERE client_id = :clientId AND org_id = :orgId)", nativeQuery = true)
    void bulkDeleteLinesByClientIdAndOrgId(@Param("clientId") UUID clientId, @Param("orgId") UUID orgId);

    @Modifying
    @Query(value = "DELETE FROM journal_entries WHERE client_id = :clientId AND org_id = :orgId", nativeQuery = true)
    int bulkDeleteByClientIdAndOrgId(@Param("clientId") UUID clientId, @Param("orgId") UUID orgId);

    @Modifying
    @Query("""
            DELETE FROM JournalLine l
            WHERE l.journalEntry.id IN (
                SELECT j.id FROM JournalEntry j
                WHERE j.clientId = :clientId
                  AND (:orgId IS NULL OR j.orgId = :orgId)
                  AND COALESCE(j.autoPosted, false) = true
            )
            """)
    void bulkDeleteAutoPostedLinesByClientIdAndOrgId(@Param("clientId") UUID clientId, @Param("orgId") UUID orgId);

    @Modifying
    @Query("""
            DELETE FROM JournalEntry j
            WHERE j.clientId = :clientId
              AND (:orgId IS NULL OR j.orgId = :orgId)
              AND COALESCE(j.autoPosted, false) = true
            """)
    int bulkDeleteAutoPostedByClientIdAndOrgId(@Param("clientId") UUID clientId, @Param("orgId") UUID orgId);
}
