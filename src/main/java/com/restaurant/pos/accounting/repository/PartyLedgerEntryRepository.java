package com.restaurant.pos.accounting.repository;

import com.restaurant.pos.accounting.domain.PartyLedgerEntry;
import com.restaurant.pos.accounting.domain.PartyType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PartyLedgerEntryRepository extends JpaRepository<PartyLedgerEntry, UUID> {

    List<PartyLedgerEntry> findByClientIdAndPartyTypeAndPartyIdOrderByEntryDateDesc(
            UUID clientId,
            PartyType partyType,
            UUID partyId
    );

    List<PartyLedgerEntry> findByClientIdAndOrgIdAndPartyTypeAndPartyIdOrderByEntryDateDesc(
            UUID clientId,
            UUID orgId,
            PartyType partyType,
            UUID partyId
    );

    Optional<PartyLedgerEntry> findTopByClientIdAndOrgIdAndPartyTypeAndPartyIdOrderByEntryDateDescCreatedAtDesc(
            UUID clientId,
            UUID orgId,
            PartyType partyType,
            UUID partyId
    );

    List<PartyLedgerEntry> findByClientIdAndOrgIdAndPartyTypeAndPartyIdOrderByEntryDateAscCreatedAtAsc(
            UUID clientId,
            UUID orgId,
            PartyType partyType,
            UUID partyId
    );

    /**
     * Serialises ledger writes for one party until the current transaction ends (PostgreSQL advisory lock),
     * so two concurrent postings cannot both read the same "previous balance".
     */
    @Query(value = "SELECT CAST(pg_advisory_xact_lock(hashtext(:lockKey)) AS text)", nativeQuery = true)
    String lockParty(@Param("lockKey") String lockKey);

    @Modifying
    @Query("""
            DELETE FROM PartyLedgerEntry p
            WHERE p.journalEntryId IN (
                SELECT j.id FROM JournalEntry j
                WHERE j.clientId = :clientId
                  AND (:orgId IS NULL OR j.orgId = :orgId)
                  AND COALESCE(j.autoPosted, false) = true
            )
            """)
    int bulkDeleteForAutoPostedJournalsByClientIdAndOrgId(@Param("clientId") UUID clientId, @Param("orgId") UUID orgId);
}
