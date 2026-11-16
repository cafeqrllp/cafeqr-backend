package com.restaurant.pos.accounting.service;

import com.restaurant.pos.accounting.domain.*;
import com.restaurant.pos.accounting.dto.AccountingAccountPeriodDto;
import com.restaurant.pos.accounting.dto.AccountingReconciliationDto;
import com.restaurant.pos.accounting.dto.AccountingSummaryDto;
import com.restaurant.pos.accounting.dto.TrialBalanceRowDto;
import com.restaurant.pos.accounting.repository.*;
import com.restaurant.pos.common.exception.BusinessException;
import com.restaurant.pos.common.exception.ResourceNotFoundException;
import com.restaurant.pos.common.service.BranchContextService;
import com.restaurant.pos.common.service.CurrencyScaleService;
import com.restaurant.pos.common.tenant.TenantContext;
import com.restaurant.pos.common.util.SecurityUtils;
import com.restaurant.pos.invoice.domain.Invoice;
import com.restaurant.pos.invoice.domain.InvoiceType;
import com.restaurant.pos.invoice.repository.InvoiceRepository;
import com.restaurant.pos.order.domain.Order;
import com.restaurant.pos.order.domain.OrderType;
import com.restaurant.pos.order.domain.Payment;
import com.restaurant.pos.order.domain.PaymentSplit;
import com.restaurant.pos.order.domain.PaymentType;
import com.restaurant.pos.order.repository.OrderRepository;
import com.restaurant.pos.order.repository.PaymentRepository;
import com.restaurant.pos.order.repository.PaymentSplitRepository;
import jakarta.persistence.criteria.Predicate;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AccountingService {

    private static final int DEFAULT_REPORT_DAYS = 31;
    private static final int MAX_REPORT_DAYS = 1830;
    private static final int MAX_JOURNAL_ROWS = 500;
    private static final Set<String> FINANCIAL_PAYMENT_METHODS = Set.of("CASH", "ONLINE", "UPI", "CARD", "BANK", "CHEQUE");
    /** Source type of the journal that carries an account's opening balance (source id = account id). */
    public static final String OPENING_BALANCE_SOURCE = "OPENING_BALANCE";

    private final AccountingAccountRepository accountRepository;
    private final JournalEntryRepository journalEntryRepository;
    private final PartyLedgerEntryRepository partyLedgerEntryRepository;
    private final PaymentAllocationRepository paymentAllocationRepository;
    private final PaymentRepository paymentRepository;
    private final PaymentSplitRepository paymentSplitRepository;
    private final InvoiceRepository invoiceRepository;
    private final OrderRepository orderRepository;
    private final BranchContextService branchContext;
    private final com.restaurant.pos.common.context.TimezoneResolver timezoneResolver;
    private final CurrencyScaleService currencyScale;
    private final AccountingDefaultsService defaultsService;
    private final Map<String, Boolean> openingBalancesEnsured = new java.util.concurrent.ConcurrentHashMap<>();


    public List<AccountingAccount> getAccounts(boolean includeInactive) {
        return getAccounts(includeInactive, TenantContext.getCurrentOrg());
    }

    public List<AccountingAccount> getAccounts(boolean includeInactive, UUID orgId) {
        UUID clientId = requireTenant();
        List<AccountingAccount> accounts;
        if (orgId == null) {
            accounts = accountRepository.findByClientIdOrderByCodeAsc(clientId);
        } else {
            accounts = accountRepository.findByClientIdAndOrgIdOrderByCodeAsc(clientId, orgId);
        }
        if (includeInactive) {
            return accounts;
        }
        return accounts.stream()
                .filter(account -> !"N".equalsIgnoreCase(account.getIsactive()))
                .collect(Collectors.toList());
    }

    public AccountingAccount getAccount(UUID id) {
        return getScopedAccount(id);
    }

    @Transactional
    public AccountingAccount createAccount(AccountingAccount account) {
        UUID clientId = requireTenant();
        UUID orgId = resolveOrg(account.getOrgId());
        account.setId(null);
        account.setClientId(clientId);
        account.setOrgId(orgId);
        normalizeAccount(account);
        ensureUniqueCode(clientId, orgId, account.getCode(), null);
        // A balance is only ever changed by journals: the account starts at zero and the
        // opening balance is posted against Opening Balance Equity so the books stay balanced.
        account.setCurrentBalance(BigDecimal.ZERO);
        if (isOpeningBalanceEquity(account)) {
            account.setOpeningBalance(BigDecimal.ZERO);
        }
        AccountingAccount saved = accountRepository.save(account);
        syncOpeningBalanceJournal(saved, true);
        return saved;
    }

    @Transactional
    public AccountingAccount updateAccount(UUID id, AccountingAccount updates) {
        AccountingAccount existing = getScopedAccount(id);
        UUID orgId = resolveOrg(updates.getOrgId() != null ? updates.getOrgId() : existing.getOrgId());
        String nextCode = trimToNull(updates.getCode()) == null ? existing.getCode() : normalizeCode(updates.getCode());
        ensureUniqueCode(existing.getClientId(), orgId, nextCode, existing.getId());

        AccountType nextType = requireAccountType(updates.getAccountType());
        String nextActive = isActiveFlag(updates.getIsactive());
        boolean system = Boolean.TRUE.equals(existing.getSystemAccount());
        if (nextType != existing.getAccountType()) {
            if (system) {
                throw new BusinessException("The type of a system account cannot be changed");
            }
            if (journalEntryRepository.countLinesForAccountExcludingSource(existing.getId(), OPENING_BALANCE_SOURCE) > 0) {
                throw new BusinessException("Account type cannot be changed after transactions have been posted to this account");
            }
        }
        if (!Objects.equals(orgId, existing.getOrgId())
                && (system || journalEntryRepository.countLinesForAccount(existing.getId()) > 0)) {
            throw new BusinessException("This account cannot be moved to another branch because it already has transactions");
        }
        if ("N".equals(nextActive) && !"N".equalsIgnoreCase(existing.getIsactive())) {
            assertCanDeactivate(existing);
        }
        // Accounts created before opening balances were journalised: post the old amount first.
        adoptLegacyOpeningBalance(existing, true);

        existing.setOrgId(orgId);
        existing.setCode(nextCode);
        existing.setName(requireText(updates.getName(), "Account name is required"));
        existing.setAccountType(nextType);
        existing.setAccountSubType(trimToNull(updates.getAccountSubType()));
        existing.setCurrencyId(updates.getCurrencyId());
        existing.setOpeningBalance(isOpeningBalanceEquity(existing) ? BigDecimal.ZERO : money(updates.getOpeningBalance()));
        // currentBalance and the system flag are never taken from the request: the balance is
        // derived from posted journals, and system accounts stay system accounts.
        existing.setCashAccount(Boolean.TRUE.equals(updates.getCashAccount()));
        existing.setBankAccount(Boolean.TRUE.equals(updates.getBankAccount()));
        existing.setBankName(trimToNull(updates.getBankName()));
        existing.setAccountNumber(trimToNull(updates.getAccountNumber()));
        existing.setIfscCode(trimToNull(updates.getIfscCode()));
        existing.setUpiId(trimToNull(updates.getUpiId()));
        existing.setDescription(trimToNull(updates.getDescription()));
        existing.setIsactive(nextActive);
        AccountingAccount saved = accountRepository.save(existing);
        syncOpeningBalanceJournal(saved, true);
        return saved;
    }

    @Transactional
    public void deactivateAccount(UUID id) {
        AccountingAccount account = getScopedAccount(id);
        assertCanDeactivate(account);
        account.setIsactive("N");
        accountRepository.save(account);
    }

    private void assertCanDeactivate(AccountingAccount account) {
        if (Boolean.TRUE.equals(account.getSystemAccount())) {
            throw new BusinessException("System accounts are used for automatic postings and cannot be deactivated");
        }
        if (money(account.getCurrentBalance()).signum() != 0) {
            throw new BusinessException("This account still has a balance. Move the balance to another account before deactivating it");
        }
    }

    /**
     * Makes sure every account with an opening balance has its opening journal
     * (Dr/Cr the account against Opening Balance Equity). Accounts created before
     * opening balances were journalised are converted here, once per branch.
     *
     * @param orgId branch to check, or null for every branch of the client
     */
    @Transactional
    public void ensureOpeningBalanceJournals(UUID orgId) {
        UUID clientId = requireTenant();
        String cacheKey = clientId + ":" + (orgId != null ? orgId : "ALL");
        if (Boolean.TRUE.equals(openingBalancesEnsured.get(cacheKey))) {
            return;
        }
        List<AccountingAccount> accounts = orgId == null
                ? accountRepository.findByClientIdOrderByCodeAsc(clientId)
                : accountRepository.findByClientIdAndOrgIdOrderByCodeAsc(clientId, orgId);
        for (AccountingAccount account : accounts) {
            adoptLegacyOpeningBalance(account, false);
        }
        openingBalancesEnsured.put(cacheKey, true);
    }

    /**
     * Legacy accounts carry their opening balance inside currentBalance with no journal behind it.
     * Take it out of the balance and post it as a proper opening journal instead.
     */
    private void adoptLegacyOpeningBalance(AccountingAccount account, boolean strict) {
        if (isOpeningBalanceEquity(account) || money(account.getOpeningBalance()).signum() == 0) {
            return;
        }
        if (account.getOrgId() == null && !strict) {
            return;
        }
        if (!journalEntryRepository.findBySourceTypeAndSourceId(OPENING_BALANCE_SOURCE, account.getId()).isEmpty()) {
            return;
        }
        account.setCurrentBalance(money(account.getCurrentBalance()).subtract(money(account.getOpeningBalance())));
        accountRepository.save(account);
        syncOpeningBalanceJournal(account, strict);
    }

    /**
     * Brings the opening journal of an account in line with its openingBalance field:
     * voids an out-of-date journal and posts a new one when the amount (or account type) changed.
     */
    private void syncOpeningBalanceJournal(AccountingAccount account, boolean strict) {
        if (isOpeningBalanceEquity(account)) {
            return;
        }
        BigDecimal opening = money(account.getOpeningBalance());
        JournalEntry active = journalEntryRepository.findBySourceTypeAndSourceId(OPENING_BALANCE_SOURCE, account.getId()).stream()
                .filter(this::isPostedActiveJournal)
                .findFirst()
                .orElse(null);
        if (active != null) {
            BigDecimal posted = BigDecimal.ZERO;
            for (JournalLine line : active.getLines()) {
                if (account.getId().equals(line.getAccountId())) {
                    posted = posted.add(accountBalanceEffect(account, money(line.getDebit()), money(line.getCredit())));
                }
            }
            if (posted.compareTo(opening) == 0 && Objects.equals(active.getOrgId(), account.getOrgId())) {
                return;
            }
            voidJournal(active, "Opening balance changed");
        }
        if (opening.signum() == 0) {
            return;
        }
        if (account.getOrgId() == null) {
            if (strict) {
                throw new BusinessException("An opening balance can only be set on an account that belongs to a branch");
            }
            return;
        }

        AccountingAccount equity = inOrg(account.getOrgId(),
                () -> defaultsService.resolveAccount(AccountingDefaultsService.OPENING_BALANCE_EQUITY));
        boolean debitNormal = account.getAccountType() == AccountType.ASSET || account.getAccountType() == AccountType.EXPENSE;
        boolean debitAccount = debitNormal == (opening.signum() > 0);
        BigDecimal amount = opening.abs();

        JournalEntry entry = JournalEntry.builder()
                .entryNo("OB-" + account.getCode())
                .entryDate(openingBalanceDate(account))
                .status(JournalStatus.POSTED)
                .sourceType(OPENING_BALANCE_SOURCE)
                .sourceId(account.getId())
                .autoPosted(false)
                .description("Opening balance for " + account.getCode() + " - " + account.getName())
                .lines(new ArrayList<>())
                .build();
        entry.setOrgId(account.getOrgId());
        entry.attachLine(JournalLine.builder()
                .accountId(account.getId())
                .debit(debitAccount ? amount : BigDecimal.ZERO)
                .credit(debitAccount ? BigDecimal.ZERO : amount)
                .description("Opening balance")
                .build());
        entry.attachLine(JournalLine.builder()
                .accountId(equity.getId())
                .debit(debitAccount ? BigDecimal.ZERO : amount)
                .credit(debitAccount ? amount : BigDecimal.ZERO)
                .description("Opening balance for " + account.getCode())
                .build());
        createJournalEntry(entry);
    }

    /** Opening entries are dated just before the branch's first posted journal, so they open every report period. */
    private LocalDateTime openingBalanceDate(AccountingAccount account) {
        LocalDateTime now = LocalDateTime.now(timezoneResolver.resolveTimezone(account.getClientId(), account.getOrgId()));
        LocalDateTime earliest = journalEntryRepository.findEarliestPostedEntryDate(
                account.getClientId(), account.getOrgId(), JournalStatus.POSTED);
        if (earliest != null && earliest.minusSeconds(1).isBefore(now)) {
            return earliest.minusSeconds(1);
        }
        return now;
    }

    /** Voids a journal and takes its effect back out of the cached account balances. */
    private void voidJournal(JournalEntry entry, String reason) {
        Set<UUID> accountIds = entry.getLines().stream()
                .map(JournalLine::getAccountId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<UUID, AccountingAccount> accountsById = accountRepository.findAllById(accountIds).stream()
                .collect(Collectors.toMap(AccountingAccount::getId, Function.identity()));
        for (JournalLine line : entry.getLines()) {
            AccountingAccount lineAccount = accountsById.get(line.getAccountId());
            if (lineAccount != null) {
                BigDecimal effect = accountBalanceEffect(lineAccount, money(line.getDebit()), money(line.getCredit()));
                lineAccount.setCurrentBalance(money(lineAccount.getCurrentBalance()).subtract(effect));
            }
        }
        accountRepository.saveAll(accountsById.values());
        entry.setStatus(JournalStatus.VOID);
        entry.setIsactive("N");
        String note = "Voided: " + reason;
        entry.setDescription(entry.getDescription() == null ? note : entry.getDescription() + " | " + note);
        journalEntryRepository.save(entry);
    }

    private boolean isOpeningBalanceEquity(AccountingAccount account) {
        return AccountingDefaultsService.OPENING_BALANCE_EQUITY.equalsIgnoreCase(account.getSystemKey());
    }

    /** Runs an action with the tenant branch context switched to the given branch. */
    private <T> T inOrg(UUID orgId, java.util.function.Supplier<T> action) {
        UUID previousOrgId = TenantContext.getCurrentOrg();
        if (Objects.equals(previousOrgId, orgId)) {
            return action.get();
        }
        TenantContext.setCurrentOrg(orgId);
        try {
            return action.get();
        } finally {
            TenantContext.setCurrentOrg(previousOrgId);
        }
    }

    public List<JournalEntry> getJournalEntries(LocalDateTime from, LocalDateTime to) {
        return getJournalEntries(from, to, "entryDate", "DESC");
    }

    public List<JournalEntry> getJournalEntries(LocalDateTime from, LocalDateTime to, String sortBy, String sortDir) {
        DateRange range = boundedRange(from, to);
        UUID clientId = requireTenant();
        UUID orgId = TenantContext.getCurrentOrg();
        Specification<JournalEntry> spec = journalSpec(clientId, orgId, range);
        Pageable page = PageRequest.of(0, MAX_JOURNAL_ROWS, journalSort(sortBy, sortDir));
        return journalEntryRepository.findAll(spec, page).getContent();
    }

    @Transactional
    public JournalEntry createJournalEntry(JournalEntry entry) {
        UUID clientId = requireTenant();
        UUID orgId = resolveOrg(entry.getOrgId());
        List<JournalLine> incomingLines = entry.getLines() == null ? List.of() : new ArrayList<>(entry.getLines());
        if (incomingLines.isEmpty()) {
            throw new BusinessException("Journal entry must have at least one line");
        }

        entry.setId(null);
        entry.setClientId(clientId);
        entry.setOrgId(orgId);
        entry.setTerminalId(entry.getTerminalId() != null ? entry.getTerminalId() : TenantContext.getCurrentTerminal());
        entry.setEntryNo(requireJournalNo(entry.getEntryNo()));
        entry.setEntryDate(entry.getEntryDate() != null ? entry.getEntryDate() : LocalDateTime.now());
        entry.setStatus(entry.getStatus() != null ? entry.getStatus() : JournalStatus.POSTED);
        entry.setSourceType(trimToNull(entry.getSourceType()));
        entry.setSourceId(entry.getSourceId());
        entry.setReversalOfJournalEntryId(entry.getReversalOfJournalEntryId());
        entry.setAutoPosted(Boolean.TRUE.equals(entry.getAutoPosted()));
        entry.setDescription(trimToNull(entry.getDescription()));
        entry.setIsactive(isActiveFlag(entry.getIsactive()));
        if (entry.getLines() == null) {
            entry.setLines(new ArrayList<>());
        }
        entry.getLines().clear();

        BigDecimal totalDebit = BigDecimal.ZERO;
        BigDecimal totalCredit = BigDecimal.ZERO;
        Map<UUID, AccountingAccount> accountsById = loadAccountsForLines(clientId, orgId, incomingLines);

        for (JournalLine line : incomingLines) {
            line.setId(null);
            line.setDebit(money(line.getDebit()));
            line.setCredit(money(line.getCredit()));
            validateJournalLine(line, accountsById);
            totalDebit = totalDebit.add(line.getDebit());
            totalCredit = totalCredit.add(line.getCredit());
            entry.attachLine(line);
        }

        if (totalDebit.compareTo(BigDecimal.ZERO) <= 0 || totalDebit.compareTo(totalCredit) != 0) {
            throw new BusinessException("Journal debits and credits must be equal and greater than zero");
        }

        entry.setTotalDebit(totalDebit);
        entry.setTotalCredit(totalCredit);
        JournalEntry saved = journalEntryRepository.save(entry);
        applyAccountBalances(saved, accountsById);
        createPartyLedgerEntries(saved, accountsById);
        return saved;
    }

    public List<PartyLedgerEntry> getPartyLedger(PartyType partyType, UUID partyId) {
        UUID clientId = requireTenant();
        UUID orgId = TenantContext.getCurrentOrg();
        if (partyType == null || partyId == null) {
            throw new BusinessException("Party type and party id are required");
        }
        if (orgId == null) {
            return partyLedgerEntryRepository.findByClientIdAndPartyTypeAndPartyIdOrderByEntryDateDesc(clientId, partyType, partyId);
        }
        return partyLedgerEntryRepository.findByClientIdAndOrgIdAndPartyTypeAndPartyIdOrderByEntryDateDesc(clientId, orgId, partyType, partyId);
    }

    public List<TrialBalanceRowDto> getTrialBalance(LocalDateTime from, LocalDateTime to) {
        DateRange range = boundedRange(from, to);
        UUID clientId = requireTenant();
        UUID orgId = TenantContext.getCurrentOrg();
        List<AccountingAccount> accounts = getAccounts(true);
        Map<UUID, TrialBalanceRowDto> rows = accounts.stream()
                .collect(Collectors.toMap(
                        AccountingAccount::getId,
                        account -> TrialBalanceRowDto.builder()
                                .accountId(account.getId())
                                .code(account.getCode())
                                .name(account.getName())
                                .accountType(account.getAccountType())
                                .debit(BigDecimal.ZERO)
                                .credit(BigDecimal.ZERO)
                                .balance(BigDecimal.ZERO)
                                .build(),
                        (left, right) -> left,
                        LinkedHashMap::new
                ));

        accountMovements(clientId, orgId, null, range.from, range.to)
                .forEach((accountId, movement) -> {
                    TrialBalanceRowDto row = rows.get(accountId);
                    if (row == null) {
                        return;
                    }
                    row.setDebit(movement.debit());
                    row.setCredit(movement.credit());
                    row.setBalance(movement.debit().subtract(movement.credit()));
                });

        List<TrialBalanceRowDto> result = new ArrayList<>(rows.values());
        if (orgId == null) {
            Map<String, List<TrialBalanceRowDto>> systemKeyGroups = new LinkedHashMap<>();
            List<TrialBalanceRowDto> nonSystemRows = new ArrayList<>();
            Map<UUID, String> accountSystemKeys = accounts.stream()
                    .filter(a -> a.getSystemKey() != null && !a.getSystemKey().isBlank())
                    .collect(Collectors.toMap(AccountingAccount::getId, AccountingAccount::getSystemKey));
            
            for (TrialBalanceRowDto row : result) {
                String sysKey = accountSystemKeys.get(row.getAccountId());
                if (sysKey != null) {
                    systemKeyGroups.computeIfAbsent(sysKey.toUpperCase(Locale.ROOT), k -> new ArrayList<>()).add(row);
                } else {
                    nonSystemRows.add(row);
                }
            }
            
            List<TrialBalanceRowDto> mergedSystemRows = new ArrayList<>();
            for (Map.Entry<String, List<TrialBalanceRowDto>> entry : systemKeyGroups.entrySet()) {
                List<TrialBalanceRowDto> group = entry.getValue();
                TrialBalanceRowDto first = group.get(0);
                BigDecimal totalDebit = BigDecimal.ZERO;
                BigDecimal totalCredit = BigDecimal.ZERO;
                BigDecimal totalBalance = BigDecimal.ZERO;
                for (TrialBalanceRowDto r : group) {
                    totalDebit = totalDebit.add(r.getDebit());
                    totalCredit = totalCredit.add(r.getCredit());
                    totalBalance = totalBalance.add(r.getBalance());
                }
                mergedSystemRows.add(TrialBalanceRowDto.builder()
                        .accountId(first.getAccountId())
                        .code(first.getCode())
                        .name(first.getName())
                        .accountType(first.getAccountType())
                        .debit(totalDebit)
                        .credit(totalCredit)
                        .balance(totalBalance)
                        .build());
            }
            List<TrialBalanceRowDto> finalRows = new ArrayList<>();
            finalRows.addAll(mergedSystemRows);
            finalRows.addAll(nonSystemRows);
            return finalRows;
        }
        return result;
    }

    public List<AccountingAccountPeriodDto> getPeriodAccounts(LocalDateTime from, LocalDateTime to, boolean includeInactive) {
        return getPeriodAccounts(from, to, includeInactive, TenantContext.getCurrentOrg(), null);
    }

    public List<AccountingAccountPeriodDto> getPeriodAccounts(LocalDateTime from, LocalDateTime to, boolean includeInactive, UUID orgId, UUID terminalId) {
        DateRange range = boundedRange(from, to);
        UUID clientId = requireTenant();
        List<AccountingAccount> accounts = getAccounts(true, orgId);
        Set<UUID> visibleAccountIds = accounts.stream()
                .filter(account -> includeInactive || !"N".equalsIgnoreCase(account.getIsactive()))
                .map(AccountingAccount::getId)
                .collect(Collectors.toCollection(LinkedHashSet::new));

        Map<UUID, BigDecimal> openingEffects = zeroAccountMap(accounts);
        Map<UUID, BigDecimal> periodDebits = zeroAccountMap(accounts);
        Map<UUID, BigDecimal> periodCredits = zeroAccountMap(accounts);
        Map<UUID, AccountingAccount> accountsById = accounts.stream()
                .collect(Collectors.toMap(AccountingAccount::getId, Function.identity(), (left, right) -> left));

        accountMovements(clientId, orgId, terminalId, null, range.from.minusNanos(1))
                .forEach((accountId, movement) -> {
                    AccountingAccount account = accountsById.get(accountId);
                    if (account != null) {
                        openingEffects.merge(account.getId(), accountBalanceEffect(account, movement.debit(), movement.credit()), BigDecimal::add);
                    }
                });

        accountMovements(clientId, orgId, terminalId, range.from, range.to)
                .forEach((accountId, movement) -> {
                    if (!accountsById.containsKey(accountId)) {
                        return;
                    }
                    periodDebits.merge(accountId, movement.debit(), BigDecimal::add);
                    periodCredits.merge(accountId, movement.credit(), BigDecimal::add);
                });

        List<AccountingAccountPeriodDto> list = accounts.stream()
                .filter(account -> visibleAccountIds.contains(account.getId()))
                .map(account -> {
                    // The opening balance is itself a journal, so it is already part of the movements before the period.
                    BigDecimal opening = openingEffects.getOrDefault(account.getId(), BigDecimal.ZERO);
                    BigDecimal debit = periodDebits.getOrDefault(account.getId(), BigDecimal.ZERO);
                    BigDecimal credit = periodCredits.getOrDefault(account.getId(), BigDecimal.ZERO);
                    BigDecimal periodNet = accountBalanceEffect(account, debit, credit);
                    return AccountingAccountPeriodDto.builder()
                            .id(account.getId())
                            .code(account.getCode())
                            .name(account.getName())
                            .accountType(account.getAccountType())
                            .accountSubType(account.getAccountSubType())
                            .systemKey(account.getSystemKey())
                            .cashAccount(account.getCashAccount())
                            .bankAccount(account.getBankAccount())
                            .isActive(account.getIsactive())
                            .openingBalance(money(account.getOpeningBalance()))
                            .periodDebit(debit)
                            .periodCredit(credit)
                            .periodNet(periodNet)
                            .periodOpening(opening)
                            .periodClosing(opening.add(periodNet))
                            .build();
                })
                .collect(Collectors.toList());

        if (orgId == null) {
            Map<String, List<AccountingAccountPeriodDto>> systemKeyGroups = new LinkedHashMap<>();
            List<AccountingAccountPeriodDto> nonSystemAccounts = new ArrayList<>();
            for (AccountingAccountPeriodDto dto : list) {
                if (dto.getSystemKey() != null && !dto.getSystemKey().isBlank()) {
                    systemKeyGroups.computeIfAbsent(dto.getSystemKey().toUpperCase(Locale.ROOT), k -> new ArrayList<>()).add(dto);
                } else {
                    nonSystemAccounts.add(dto);
                }
            }
            List<AccountingAccountPeriodDto> mergedAccounts = new ArrayList<>();
            for (Map.Entry<String, List<AccountingAccountPeriodDto>> entry : systemKeyGroups.entrySet()) {
                List<AccountingAccountPeriodDto> group = entry.getValue();
                AccountingAccountPeriodDto first = group.get(0);
                BigDecimal openingBalance = BigDecimal.ZERO;
                BigDecimal periodDebit = BigDecimal.ZERO;
                BigDecimal periodCredit = BigDecimal.ZERO;
                BigDecimal periodNet = BigDecimal.ZERO;
                BigDecimal periodOpening = BigDecimal.ZERO;
                BigDecimal periodClosing = BigDecimal.ZERO;
                for (AccountingAccountPeriodDto dto : group) {
                    openingBalance = openingBalance.add(dto.getOpeningBalance());
                    periodDebit = periodDebit.add(dto.getPeriodDebit());
                    periodCredit = periodCredit.add(dto.getPeriodCredit());
                    periodNet = periodNet.add(dto.getPeriodNet());
                    periodOpening = periodOpening.add(dto.getPeriodOpening());
                    periodClosing = periodClosing.add(dto.getPeriodClosing());
                }
                mergedAccounts.add(AccountingAccountPeriodDto.builder()
                        .id(first.getId())
                        .code(first.getCode())
                        .name(first.getName())
                        .accountType(first.getAccountType())
                        .accountSubType(first.getAccountSubType())
                        .systemKey(first.getSystemKey())
                        .cashAccount(first.getCashAccount())
                        .bankAccount(first.getBankAccount())
                        .isActive(first.getIsActive())
                        .openingBalance(openingBalance)
                        .periodDebit(periodDebit)
                        .periodCredit(periodCredit)
                        .periodNet(periodNet)
                        .periodOpening(periodOpening)
                        .periodClosing(periodClosing)
                        .build());
            }
            List<AccountingAccountPeriodDto> finalResult = new ArrayList<>();
            finalResult.addAll(mergedAccounts);
            finalResult.addAll(nonSystemAccounts);
            return finalResult;
        }
        return list;
    }

    public AccountingSummaryDto getSummary(LocalDateTime from, LocalDateTime to) {
        return getSummary(from, to, TenantContext.getCurrentOrg(), null);
    }

    public AccountingSummaryDto getSummary(LocalDateTime from, LocalDateTime to, UUID orgId, UUID terminalId) {
        DateRange range = boundedRange(from, to);
        UUID clientId = requireTenant();
        List<AccountingAccountPeriodDto> accounts = getPeriodAccounts(range.from, range.to, true, orgId, terminalId);
        Map<String, AccountingAccountPeriodDto> accountsBySystemKey = accounts.stream()
                .filter(account -> account.getSystemKey() != null && !account.getSystemKey().isBlank())
                .collect(Collectors.toMap(
                        account -> account.getSystemKey().toUpperCase(Locale.ROOT),
                        Function.identity(),
                        (left, right) -> left
                ));

        // ── Order-based sales figures (matches ReportService / reports page exactly) ──────────
        List<Order> saleOrders = findCompletedSaleOrdersInPeriod(clientId, orgId, terminalId, range);
        BigDecimal billedTotal   = BigDecimal.ZERO;
        BigDecimal discounts     = BigDecimal.ZERO;
        BigDecimal outputTax     = BigDecimal.ZERO;
        BigDecimal roundOff      = BigDecimal.ZERO;
        for (Order o : saleOrders) {
            billedTotal = billedTotal.add(money(o.getGrandTotal()));
            discounts   = discounts.add(calculateTaxExclusiveDiscount(o));
            outputTax   = outputTax.add(money(o.getTotalTaxAmount()));
            roundOff    = roundOff.add(money(o.getRoundOffAmount()));
        }
        // Indian GAAP / Ind AS 115: Sales Revenue (Gross Sales & Net Sales) must EXCLUDE output tax and round-off.
        // GST collected is a liability payable to the government, and Round Off is a rounding adjustment.
        // netSales   = billedTotal − tax − roundOff
        // grossSales = netSales + discounts
        BigDecimal netSales   = billedTotal.subtract(outputTax).subtract(roundOff);   // ex-tax, ex-roundoff
        BigDecimal grossSales = netSales.add(discounts);           // ex-tax, ex-roundoff pre-discount value

        // ── Journal-only values (ledger is the only source for these) ────────────────────────
        BigDecimal inputTax  = movement(accountsBySystemKey, AccountingDefaultsService.INPUT_TAX);
        BigDecimal cogsPurchases  = movement(accountsBySystemKey, AccountingDefaultsService.PURCHASE_COGS);
        BigDecimal stockAdjustment = movement(accountsBySystemKey, AccountingDefaultsService.STOCK_ADJUSTMENT_GAIN_LOSS);
        BigDecimal operatingExpenses = BigDecimal.ZERO;
        for (AccountingAccountPeriodDto account : accounts) {
            if (account.getAccountType() == AccountType.EXPENSE) {
                String sysKey = account.getSystemKey();
                if (sysKey != null) {
                    String normKey = sysKey.toUpperCase(Locale.ROOT);
                    if (normKey.equals(AccountingDefaultsService.PURCHASE_COGS) ||
                        normKey.equals(AccountingDefaultsService.DISCOUNT_ALLOWED) ||
                        normKey.equals(AccountingDefaultsService.ROUND_OFF) ||
                        normKey.equals(AccountingDefaultsService.STOCK_ADJUSTMENT_GAIN_LOSS)) {
                        continue;
                    }
                }
                operatingExpenses = operatingExpenses.add(money(account.getPeriodNet()));
            }
        }
        BigDecimal expenses = operatingExpenses;
        // Profit = billedTotal (includes round-off) − outputTax (GST liability) − COGS − opex − stockAdj
        // This is mathematically equivalent to: netSales + roundOff − COGS − opex − stockAdj
        BigDecimal profit = billedTotal.subtract(outputTax).subtract(cogsPurchases).subtract(operatingExpenses)
                .subtract(stockAdjustment.max(BigDecimal.ZERO));
        BigDecimal receivable    = closing(accountsBySystemKey, AccountingDefaultsService.ACCOUNTS_RECEIVABLE);
        BigDecimal payable       = closing(accountsBySystemKey, AccountingDefaultsService.ACCOUNTS_PAYABLE);
        BigDecimal inventoryValue = closing(accountsBySystemKey, AccountingDefaultsService.INVENTORY_ASSET);

        Map<String, BigDecimal> paymentBreakdown = paymentBreakdown(clientId, orgId, terminalId, range);
        BigDecimal paymentCollected = paymentBreakdown.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        long transactions = journalEntryRepository.countPostedActive(clientId, orgId, terminalId, range.from, range.to, JournalStatus.POSTED);

        return AccountingSummaryDto.builder()
                .from(range.from)
                .to(range.to)
                .transactions(transactions)
                .grossSales(grossSales)
                .discounts(discounts)
                .netSales(netSales)
                .outputTax(outputTax)
                .inputTax(inputTax)
                .roundOff(roundOff)
                .billedTotal(billedTotal)
                .paymentCollected(paymentCollected)
                .cashCollected(paymentBreakdown.getOrDefault("CASH", BigDecimal.ZERO))
                .onlineCollected(paymentBreakdown.getOrDefault("ONLINE", BigDecimal.ZERO))
                .upiCollected(paymentBreakdown.getOrDefault("UPI", BigDecimal.ZERO))
                .cardCollected(paymentBreakdown.getOrDefault("CARD", BigDecimal.ZERO))
                .bankCollected(paymentBreakdown.getOrDefault("BANK", BigDecimal.ZERO))
                .chequeCollected(paymentBreakdown.getOrDefault("CHEQUE", BigDecimal.ZERO))
                .expenses(expenses)
                .cogsPurchases(cogsPurchases)
                .profit(profit)
                .receivable(receivable)
                .payable(payable)
                .inventoryValue(inventoryValue)
                .paymentBreakdown(paymentBreakdown)
                .build();
    }

    public AccountingReconciliationDto getReconciliation(LocalDateTime from, LocalDateTime to) {
        return getReconciliation(from, to, TenantContext.getCurrentOrg(), null);
    }

    public AccountingReconciliationDto getReconciliation(LocalDateTime from, LocalDateTime to, UUID orgId, UUID terminalId) {
        DateRange range = boundedRange(from, to);
        UUID clientId = requireTenant();
        List<Order> salesOrders = findCompletedSaleOrdersInPeriod(clientId, orgId, terminalId, range);
        List<Invoice> invoices = findInvoicesInPeriodIncludingOrderPeriod(clientId, orgId, terminalId, range, salesOrders);
        List<Payment> payments = findPaymentsInPeriodIncludingOrderPeriod(clientId, orgId, terminalId, range, salesOrders);
        Set<String> invoiceSourceTypes = Set.of(InvoiceType.CUSTOMER_INVOICE.name(), InvoiceType.VENDOR_BILL.name(), InvoiceType.EXPENSE_RECEIPT.name());
        Set<String> paymentSourceTypes = Set.of("INBOUND_PAYMENT", "OUTBOUND_PAYMENT");
        Set<String> trackedSourceTypes = new LinkedHashSet<>();
        trackedSourceTypes.addAll(invoiceSourceTypes);
        trackedSourceTypes.addAll(paymentSourceTypes);
        List<JournalEntryRepository.PostedSourceProjection> postedSources =
                journalEntryRepository.findPostedSources(clientId, orgId, terminalId, range.from, range.to, JournalStatus.POSTED, trackedSourceTypes);

        Set<UUID> postedInvoiceIds = postedSources.stream()
                .filter(entry -> invoiceSourceTypes.contains(normalizeSourceType(entry.getSourceType())))
                .map(JournalEntryRepository.PostedSourceProjection::getSourceId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Set<UUID> postedPaymentIds = postedSources.stream()
                .filter(entry -> paymentSourceTypes.contains(normalizeSourceType(entry.getSourceType())))
                .map(JournalEntryRepository.PostedSourceProjection::getSourceId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        long missingInvoices = invoices.stream().map(Invoice::getId).filter(id -> !postedInvoiceIds.contains(id)).count();
        long missingPayments = payments.stream().map(Payment::getId).filter(id -> !postedPaymentIds.contains(id)).count();
        List<String> warnings = new ArrayList<>();
        if (missingInvoices > 0) {
            warnings.add(missingInvoices + " invoice(s) in this period do not have accounting journal entries.");
        }
        if (missingPayments > 0) {
            warnings.add(missingPayments + " payment(s) in this period do not have accounting journal entries.");
        }
        Set<UUID> completedSalesOrderIds = salesOrders.stream()
                .map(Order::getId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<UUID, List<PaymentSplit>> splitsByPaymentId = loadPaymentSplitsByPaymentId(payments);
        BigDecimal billedSalesTotal = salesOrders.stream()
                .map(Order::getGrandTotal)
                .map(this::money)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal linkedSalesPaymentsTotal = BigDecimal.ZERO;
        BigDecimal otherActivePaymentsTotal = BigDecimal.ZERO;
        long unmatchedPaymentCount = 0;
        for (Payment payment : payments) {
            if (!isInboundPayment(payment)) {
                continue;
            }
            BigDecimal paymentAmount = paymentFinancialAmount(payment, splitsByPaymentId);
            if (paymentAmount.compareTo(BigDecimal.ZERO) <= 0) {
                continue;
            }
            UUID paymentOrderId = payment.getOrderId();
            if (paymentOrderId != null && completedSalesOrderIds.contains(paymentOrderId)) {
                linkedSalesPaymentsTotal = linkedSalesPaymentsTotal.add(paymentAmount);
            } else {
                otherActivePaymentsTotal = otherActivePaymentsTotal.add(paymentAmount);
                unmatchedPaymentCount++;
            }
        }
        BigDecimal paymentCollectedTotal = linkedSalesPaymentsTotal.add(otherActivePaymentsTotal);
        boolean hasOtherActivePayments = otherActivePaymentsTotal.compareTo(BigDecimal.ZERO) > 0;
        if (hasOtherActivePayments) {
            warnings.add(unmatchedPaymentCount + " active payment(s) worth " + otherActivePaymentsTotal
                    + " are not linked to completed sales in this period.");
        }

        return AccountingReconciliationDto.builder()
                .from(range.from)
                .to(range.to)
                .salesOrders(salesOrders.size())
                .invoices(invoices.size())
                .payments(payments.size())
                .postedInvoices(postedInvoiceIds.size())
                .postedPayments(postedPaymentIds.size())
                .missingInvoices(missingInvoices)
                .missingPayments(missingPayments)
                .billedSalesTotal(billedSalesTotal)
                .linkedSalesPaymentsTotal(linkedSalesPaymentsTotal)
                .otherActivePaymentsTotal(otherActivePaymentsTotal)
                .paymentCollectedTotal(paymentCollectedTotal)
                .unmatchedPaymentCount(unmatchedPaymentCount)
                .outOfSync(missingInvoices > 0 || missingPayments > 0 || hasOtherActivePayments)
                .warnings(warnings)
                .build();
    }

    @Transactional
    public PaymentAllocation allocatePayment(PaymentAllocation allocation) {
        UUID clientId = requireTenant();
        UUID orgId = resolveOrg(allocation.getOrgId());
        if (allocation.getPaymentId() == null) {
            throw new BusinessException("Payment id is required");
        }
        if (money(allocation.getAllocatedAmount()).compareTo(BigDecimal.ZERO) <= 0) {
            throw new BusinessException("Allocated amount must be greater than zero");
        }
        allocation.setId(null);
        allocation.setClientId(clientId);
        allocation.setOrgId(orgId);
        allocation.setAllocatedAmount(money(allocation.getAllocatedAmount()));
        allocation.setAllocationDate(allocation.getAllocationDate() != null ? allocation.getAllocationDate() : LocalDateTime.now());
        allocation.setStatus(trimToNull(allocation.getStatus()) == null ? "POSTED" : allocation.getStatus().trim().toUpperCase(Locale.ROOT));
        allocation.setIsactive(isActiveFlag(allocation.getIsactive()));
        return paymentAllocationRepository.save(allocation);
    }

    private List<Order> findCompletedSaleOrdersInPeriod(UUID clientId, UUID orgId, UUID terminalId, DateRange range) {
        UUID resolvedOrgId = orgId != null ? orgId : TenantContext.getCurrentOrg();
        ZoneId zoneId = timezoneResolver.resolveTimezone(clientId, resolvedOrgId);
        Instant instantFrom = range.from.atZone(zoneId).toInstant();
        Instant instantTo = range.to.atZone(zoneId).toInstant();
        return orderRepository.findAll((root, query, cb) -> {
            // Eagerly fetch the order lines to avoid LazyInitializationException and N+1 queries
            if (Long.class != query.getResultType() && void.class != query.getResultType()) {
                root.fetch("lines", jakarta.persistence.criteria.JoinType.LEFT);
            }
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(cb.equal(root.get("clientId"), clientId));
            if (orgId != null) {
                predicates.add(cb.equal(root.get("orgId"), orgId));
            }
            if (terminalId != null) {
                predicates.add(cb.equal(root.get("terminalId"), terminalId));
            }
            predicates.add(cb.equal(root.get("orderType"), OrderType.SALE));
            predicates.add(cb.equal(root.get("orderStatus"), "COMPLETED"));
            predicates.add(cb.equal(root.get("isactive"), "Y"));
            predicates.add(cb.greaterThanOrEqualTo(root.get("orderDate"), instantFrom));
            predicates.add(cb.lessThanOrEqualTo(root.get("orderDate"), instantTo));
            return cb.and(predicates.toArray(new Predicate[0]));
        });
    }

    private List<Invoice> findInvoicesInPeriodIncludingOrderPeriod(UUID clientId, UUID orgId, UUID terminalId, DateRange range, Collection<Order> orders) {
        Map<UUID, Invoice> invoicesById = new LinkedHashMap<>();
        invoiceRepository.findAll((root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(cb.equal(root.get("clientId"), clientId));
            if (orgId != null) {
                predicates.add(cb.equal(root.get("orgId"), orgId));
            }
            if (terminalId != null) {
                predicates.add(cb.equal(root.get("terminalId"), terminalId));
            }
            predicates.add(cb.greaterThanOrEqualTo(root.get("invoiceDate"), range.from));
            predicates.add(cb.lessThanOrEqualTo(root.get("invoiceDate"), range.to));
            predicates.add(cb.or(cb.isNull(root.get("status")), cb.notEqual(cb.upper(root.get("status").as(String.class)), "VOID")));
            predicates.add(cb.or(cb.isNull(root.get("docStatus")), cb.notEqual(cb.upper(root.get("docStatus").as(String.class)), "VOIDED")));
            return cb.and(predicates.toArray(new Predicate[0]));
        }).stream()
                .filter(invoice -> isScopedInvoice(invoice, clientId, orgId, terminalId))
                .filter(this::isActiveInvoice)
                .forEach(invoice -> invoicesById.put(invoice.getId(), invoice));

        for (Order order : orders) {
            invoiceRepository.findByOrderId(order.getId()).stream()
                    .filter(invoice -> isScopedInvoice(invoice, clientId, orgId, terminalId))
                    .filter(this::isActiveInvoice)
                    .forEach(invoice -> invoicesById.put(invoice.getId(), invoice));
        }
        return new ArrayList<>(invoicesById.values());
    }

    private List<Payment> findPaymentsInPeriodIncludingOrderPeriod(UUID clientId, UUID orgId, UUID terminalId, DateRange range, Collection<Order> orders) {
        Map<UUID, Payment> paymentsById = new LinkedHashMap<>();
        paymentRepository.findActivePaymentsInPeriod(clientId, orgId, range.from, range.to).stream()
                .filter(payment -> isScopedPayment(payment, clientId, orgId, terminalId))
                .filter(this::isActivePayment)
                .forEach(payment -> paymentsById.put(payment.getId(), payment));

        for (Order order : orders) {
            paymentRepository.findByOrderId(order.getId()).stream()
                    .filter(payment -> isScopedPayment(payment, clientId, orgId, terminalId))
                    .filter(this::isActivePayment)
                    .forEach(payment -> paymentsById.put(payment.getId(), payment));
        }
        return new ArrayList<>(paymentsById.values());
    }

    private boolean isScopedInvoice(Invoice invoice, UUID clientId, UUID orgId, UUID terminalId) {
        return invoice != null
                && clientId.equals(invoice.getClientId())
                && (orgId == null || orgId.equals(invoice.getOrgId()))
                && (terminalId == null || terminalId.equals(invoice.getTerminalId()));
    }

    private boolean isScopedPayment(Payment payment, UUID clientId, UUID orgId, UUID terminalId) {
        return payment != null
                && clientId.equals(payment.getClientId())
                && (orgId == null || orgId.equals(payment.getOrgId()))
                && (terminalId == null || terminalId.equals(payment.getTerminalId()));
    }

    private boolean isActiveInvoice(Invoice invoice) {
        return invoice != null
                && !"N".equalsIgnoreCase(invoice.getIsactive())
                && !"VOID".equalsIgnoreCase(invoice.getStatus())
                && !"VOID".equalsIgnoreCase(invoice.getDocStatus())
                && !"VOIDED".equalsIgnoreCase(invoice.getDocStatus());
    }

    private boolean isActivePayment(Payment payment) {
        return payment != null
                && !"N".equalsIgnoreCase(payment.getIsactive())
                && !"VOID".equalsIgnoreCase(payment.getDocStatus())
                && !"VOIDED".equalsIgnoreCase(payment.getDocStatus());
    }

    private boolean isInboundPayment(Payment payment) {
        return payment != null && (payment.getPaymentType() == null || payment.getPaymentType() == PaymentType.INBOUND);
    }

    private Specification<JournalEntry> journalSpec(UUID clientId, UUID orgId, DateRange range) {
        return journalSpec(clientId, orgId, range.from, range.to);
    }

    private Specification<JournalEntry> journalSpec(UUID clientId, UUID orgId, LocalDateTime from, LocalDateTime to) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(cb.equal(root.get("clientId"), clientId));
            if (orgId != null) {
                predicates.add(cb.equal(root.get("orgId"), orgId));
            }
            if (from != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("entryDate"), from));
            }
            if (to != null) {
                predicates.add(cb.lessThanOrEqualTo(root.get("entryDate"), to));
            }
            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }

    private Sort journalSort(String sortBy, String sortDir) {
        String field = switch (sortBy != null ? sortBy.trim() : "") {
            case "entryNo" -> "entryNo";
            case "totalDebit" -> "totalDebit";
            case "totalCredit" -> "totalCredit";
            case "createdAt" -> "createdAt";
            default -> "entryDate";
        };
        Sort.Direction direction = "ASC".equalsIgnoreCase(sortDir) ? Sort.Direction.ASC : Sort.Direction.DESC;
        return Sort.by(new Sort.Order(direction, field), new Sort.Order(direction, "createdAt"));
    }

    private boolean isPostedActiveJournal(JournalEntry entry) {
        return entry != null
                && entry.getStatus() == JournalStatus.POSTED
                && !"N".equalsIgnoreCase(entry.getIsactive());
    }

    private Map<UUID, BigDecimal> zeroAccountMap(List<AccountingAccount> accounts) {
        Map<UUID, BigDecimal> values = new LinkedHashMap<>();
        for (AccountingAccount account : accounts) {
            values.put(account.getId(), BigDecimal.ZERO);
        }
        return values;
    }

    private Map<UUID, AccountMovement> accountMovements(UUID clientId, UUID orgId, UUID terminalId, LocalDateTime from, LocalDateTime to) {
        Map<UUID, AccountMovement> movements = new LinkedHashMap<>();
        journalEntryRepository.sumLineMovements(clientId, orgId, terminalId, from, to, JournalStatus.POSTED)
                .forEach(row -> movements.put(row.getAccountId(), new AccountMovement(money(row.getDebit()), money(row.getCredit()))));
        return movements;
    }

    private String normalizeSourceType(String sourceType) {
        return sourceType == null ? "" : sourceType.trim().toUpperCase(Locale.ROOT);
    }

    private BigDecimal movement(Map<String, AccountingAccountPeriodDto> accountsBySystemKey, String systemKey) {
        AccountingAccountPeriodDto account = accountsBySystemKey.get(systemKey);
        return account != null ? money(account.getPeriodNet()) : BigDecimal.ZERO;
    }

    private BigDecimal closing(Map<String, AccountingAccountPeriodDto> accountsBySystemKey, String systemKey) {
        AccountingAccountPeriodDto account = accountsBySystemKey.get(systemKey);
        return account != null ? money(account.getPeriodClosing()) : BigDecimal.ZERO;
    }

    private Map<String, BigDecimal> paymentBreakdown(UUID clientId, UUID orgId, UUID terminalId, DateRange range) {
        Map<String, BigDecimal> totals = new LinkedHashMap<>();
        FINANCIAL_PAYMENT_METHODS.forEach(method -> totals.put(method, BigDecimal.ZERO));
        List<Order> salesOrders = findCompletedSaleOrdersInPeriod(clientId, orgId, terminalId, range);
        List<Payment> payments = findPaymentsInPeriodIncludingOrderPeriod(clientId, orgId, terminalId, range, salesOrders);
        Map<UUID, List<PaymentSplit>> splitsByPaymentId = loadPaymentSplitsByPaymentId(payments);
        for (Payment payment : payments) {
            if (!isInboundPayment(payment)) {
                continue;
            }
            List<PaymentSplit> splits = splitsByPaymentId.getOrDefault(payment.getId(), List.of());
            if (splits.isEmpty()) {
                addPaymentAmount(totals, normalizeFinancialPaymentMethod(payment.getPaymentMethod()), payment.getAmountPaid());
            } else {
                for (PaymentSplit split : splits) {
                    addPaymentAmount(totals, normalizeFinancialPaymentMethod(split.getPaymentMethod()), split.getAmount());
                }
            }
        }
        return totals;
    }

    private Map<UUID, List<PaymentSplit>> loadPaymentSplitsByPaymentId(Collection<Payment> payments) {
        Set<UUID> paymentIds = payments.stream()
                .filter(this::isInboundPayment)
                .map(Payment::getId)
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (paymentIds.isEmpty()) {
            return Map.of();
        }
        return paymentSplitRepository.findByPaymentIdInOrderByCreatedAtAsc(paymentIds).stream()
                .collect(Collectors.groupingBy(PaymentSplit::getPaymentId, LinkedHashMap::new, Collectors.toList()));
    }

    private BigDecimal paymentFinancialAmount(Payment payment, Map<UUID, List<PaymentSplit>> splitsByPaymentId) {
        if (!isInboundPayment(payment)) {
            return BigDecimal.ZERO;
        }
        List<PaymentSplit> splits = splitsByPaymentId.getOrDefault(payment.getId(), List.of());
        if (splits.isEmpty()) {
            return positiveMoney(payment.getAmountPaid());
        }
        return splits.stream()
                .map(PaymentSplit::getAmount)
                .map(this::positiveMoney)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private void addPaymentAmount(Map<String, BigDecimal> totals, String method, BigDecimal amount) {
        BigDecimal value = positiveMoney(amount);
        if (value.compareTo(BigDecimal.ZERO) <= 0) {
            return;
        }
        totals.merge(method, value, BigDecimal::add);
    }

    private BigDecimal positiveMoney(BigDecimal amount) {
        BigDecimal value = money(amount);
        return value.compareTo(BigDecimal.ZERO) > 0 ? value : BigDecimal.ZERO;
    }

    private String normalizeFinancialPaymentMethod(String paymentMethod) {
        if (paymentMethod == null || paymentMethod.isBlank()) {
            return "CASH";
        }
        String method = paymentMethod.trim().toUpperCase(Locale.ROOT);
        if (FINANCIAL_PAYMENT_METHODS.contains(method)) {
            return method;
        }
        if ("MIXED".equals(method)) {
            return "ONLINE";
        }
        return "CASH";
    }

    public BigDecimal calculateTaxExclusiveDiscount(Order order) {
        int dp = order == null ? CurrencyScaleService.FALLBACK_DECIMAL_PLACES
                : currencyScale.decimalPlaces(order.getClientId(), order.getOrgId());
        if (order == null) {
            return BigDecimal.ZERO.setScale(dp, RoundingMode.HALF_UP);
        }
        if (order.getLines() == null || order.getLines().isEmpty()) {
            return money(order.getTotalDiscountAmount()).setScale(dp, RoundingMode.HALF_UP);
        }
        BigDecimal totalExTaxDiscount = BigDecimal.ZERO;
        for (com.restaurant.pos.order.domain.OrderLine line : order.getLines()) {
            if (line.getGrossLineAmount() == null || line.getTaxType() == null) {
                BigDecimal disc = money(line.getDiscountAmount());
                BigDecimal taxRate = money(line.getTaxRate());
                BigDecimal discExTax = disc.divide(BigDecimal.ONE.add(taxRate.divide(BigDecimal.valueOf(100))), 4, RoundingMode.HALF_UP);
                totalExTaxDiscount = totalExTaxDiscount.add(discExTax);
                continue;
            }
            BigDecimal grossLine = money(line.getGrossLineAmount());
            BigDecimal taxable = money(line.getTaxableAmount());
            BigDecimal taxRate = money(line.getTaxRate());
            
            // Note: Since grossLineAmount is populated with the Gross Face Amount (tax-inclusive) for both
            // INCLUSIVE and EXCLUSIVE tax lines, we must always divide by the tax factor (1 + taxRate/100)
            // to obtain the true tax-exclusive gross amount.
            BigDecimal lineGrossExTax = grossLine.divide(BigDecimal.ONE.add(taxRate.divide(BigDecimal.valueOf(100))), 4, RoundingMode.HALF_UP);
            
            BigDecimal lineDiscountExTax = lineGrossExTax.subtract(taxable).max(BigDecimal.ZERO);
            totalExTaxDiscount = totalExTaxDiscount.add(lineDiscountExTax);
        }
        if (totalExTaxDiscount.compareTo(BigDecimal.ZERO) == 0 && order.getTotalDiscountAmount() != null) {
            totalExTaxDiscount = money(order.getTotalDiscountAmount());
        }
        return totalExTaxDiscount.setScale(dp, RoundingMode.HALF_UP);
    }

    private AccountingAccount getScopedAccount(UUID id) {
        UUID clientId = requireTenant();
        UUID orgId = TenantContext.getCurrentOrg();
        if (orgId == null) {
            return accountRepository.findByIdAndClientId(id, clientId)
                    .orElseThrow(() -> new ResourceNotFoundException("Accounting account not found"));
        }
        return accountRepository.findByIdAndClientIdAndOrgId(id, clientId, orgId)
                .orElseThrow(() -> new ResourceNotFoundException("Accounting account not found"));
    }

    private Map<UUID, AccountingAccount> loadAccountsForLines(UUID clientId, UUID orgId, List<JournalLine> lines) {
        Set<UUID> accountIds = lines.stream()
                .map(JournalLine::getAccountId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        if (accountIds.isEmpty()) {
            throw new BusinessException("Every journal line must use an account");
        }
        Map<UUID, AccountingAccount> accountsById = accountRepository.findAllById(accountIds).stream()
                .filter(account -> clientId.equals(account.getClientId()))
                .filter(account -> Objects.equals(orgId, account.getOrgId()))
                .collect(Collectors.toMap(AccountingAccount::getId, Function.identity()));
        if (accountsById.size() != accountIds.size()) {
            throw new BusinessException("One or more journal accounts are invalid for this branch");
        }
        return accountsById;
    }

    private void validateJournalLine(JournalLine line, Map<UUID, AccountingAccount> accountsById) {
        if (line.getAccountId() == null || !accountsById.containsKey(line.getAccountId())) {
            throw new BusinessException("Every journal line must use a valid account");
        }
        if (line.getDebit().compareTo(BigDecimal.ZERO) < 0 || line.getCredit().compareTo(BigDecimal.ZERO) < 0) {
            throw new BusinessException("Journal debit and credit cannot be negative");
        }
        boolean hasDebit = line.getDebit().compareTo(BigDecimal.ZERO) > 0;
        boolean hasCredit = line.getCredit().compareTo(BigDecimal.ZERO) > 0;
        if (hasDebit == hasCredit) {
            throw new BusinessException("Each journal line must have either debit or credit");
        }
    }

    private void applyAccountBalances(JournalEntry saved, Map<UUID, AccountingAccount> accountsById) {
        for (JournalLine line : saved.getLines()) {
            AccountingAccount account = accountsById.get(line.getAccountId());
            BigDecimal effect = accountBalanceEffect(account, line.getDebit(), line.getCredit());
            account.setCurrentBalance(money(account.getCurrentBalance()).add(effect));
        }
        accountRepository.saveAll(accountsById.values());
    }

    private BigDecimal accountBalanceEffect(AccountingAccount account, BigDecimal debit, BigDecimal credit) {
        boolean debitNormal = account.getAccountType() == AccountType.ASSET || account.getAccountType() == AccountType.EXPENSE;
        return debitNormal ? debit.subtract(credit) : credit.subtract(debit);
    }

    private void createPartyLedgerEntries(JournalEntry saved, Map<UUID, AccountingAccount> accountsById) {
        Map<String, List<JournalLine>> linesByParty = new LinkedHashMap<>();
        for (JournalLine line : saved.getLines()) {
            if (line.getPartyType() == null || line.getPartyId() == null) {
                continue;
            }
            linesByParty.computeIfAbsent(line.getPartyType() + ":" + line.getPartyId(), key -> new ArrayList<>()).add(line);
        }
        for (Map.Entry<String, List<JournalLine>> group : linesByParty.entrySet()) {
            PartyType partyType = group.getValue().get(0).getPartyType();
            UUID partyId = group.getValue().get(0).getPartyId();
            // One writer per party at a time, otherwise two postings read the same previous balance.
            partyLedgerEntryRepository.lockParty(saved.getClientId() + ":" + saved.getOrgId() + ":" + group.getKey());

            Optional<PartyLedgerEntry> latest = partyLedgerEntryRepository
                    .findTopByClientIdAndOrgIdAndPartyTypeAndPartyIdOrderByEntryDateDescCreatedAtDesc(
                            saved.getClientId(), saved.getOrgId(), partyType, partyId);
            boolean backdated = latest.isPresent()
                    && latest.get().getEntryDate() != null
                    && saved.getEntryDate().isBefore(latest.get().getEntryDate());
            BigDecimal running = latest.map(PartyLedgerEntry::getBalanceAfter).map(this::money).orElse(BigDecimal.ZERO);

            List<PartyLedgerEntry> entries = new ArrayList<>();
            for (JournalLine line : group.getValue()) {
                running = running.add(line.getDebit()).subtract(line.getCredit());
                PartyLedgerEntry entry = PartyLedgerEntry.builder()
                        .partyType(partyType)
                        .partyId(partyId)
                        .accountId(accountsById.get(line.getAccountId()).getId())
                        .journalEntryId(saved.getId())
                        .sourceType(saved.getSourceType())
                        .sourceId(saved.getSourceId())
                        .entryDate(saved.getEntryDate())
                        .debit(line.getDebit())
                        .credit(line.getCredit())
                        .balanceAfter(running)
                        .description(line.getDescription())
                        .isactive("Y")
                        .build();
                entry.setClientId(saved.getClientId());
                entry.setOrgId(saved.getOrgId());
                entries.add(entry);
            }
            partyLedgerEntryRepository.saveAllAndFlush(entries);
            if (backdated) {
                // The new entry sits in the middle of the ledger: every later running balance moves too.
                recalculatePartyBalances(saved.getClientId(), saved.getOrgId(), partyType, partyId);
            }
        }
    }

    private void recalculatePartyBalances(UUID clientId, UUID orgId, PartyType partyType, UUID partyId) {
        List<PartyLedgerEntry> ledger = partyLedgerEntryRepository
                .findByClientIdAndOrgIdAndPartyTypeAndPartyIdOrderByEntryDateAscCreatedAtAsc(clientId, orgId, partyType, partyId);
        BigDecimal running = BigDecimal.ZERO;
        List<PartyLedgerEntry> changed = new ArrayList<>();
        for (PartyLedgerEntry entry : ledger) {
            running = running.add(money(entry.getDebit())).subtract(money(entry.getCredit()));
            if (entry.getBalanceAfter() == null || entry.getBalanceAfter().compareTo(running) != 0) {
                entry.setBalanceAfter(running);
                changed.add(entry);
            }
        }
        partyLedgerEntryRepository.saveAll(changed);
    }

    private void normalizeAccount(AccountingAccount account) {
        account.setCode(normalizeCode(account.getCode()));
        account.setName(requireText(account.getName(), "Account name is required"));
        account.setAccountType(requireAccountType(account.getAccountType()));
        account.setAccountSubType(trimToNull(account.getAccountSubType()));
        account.setSystemKey(trimToNull(account.getSystemKey()));
        account.setOpeningBalance(money(account.getOpeningBalance()));
        account.setCurrentBalance(BigDecimal.ZERO);
        // Accounts created through the API are never system accounts; those come from the defaults.
        account.setSystemAccount(false);
        account.setSystemKey(null);
        account.setCashAccount(Boolean.TRUE.equals(account.getCashAccount()));
        account.setBankAccount(Boolean.TRUE.equals(account.getBankAccount()));
        account.setBankName(trimToNull(account.getBankName()));
        account.setAccountNumber(trimToNull(account.getAccountNumber()));
        account.setIfscCode(trimToNull(account.getIfscCode()));
        account.setUpiId(trimToNull(account.getUpiId()));
        account.setDescription(trimToNull(account.getDescription()));
        account.setIsactive(isActiveFlag(account.getIsactive()));
    }

    private void ensureUniqueCode(UUID clientId, UUID orgId, String code, UUID existingId) {
        boolean duplicate = existingId == null
                ? accountRepository.existsByClientIdAndOrgIdAndCodeIgnoreCase(clientId, orgId, code)
                : accountRepository.existsByClientIdAndOrgIdAndCodeIgnoreCaseAndIdNot(clientId, orgId, code, existingId);
        if (duplicate) {
            throw new BusinessException("An accounting account with this code already exists");
        }
    }

    private DateRange boundedRange(LocalDateTime from, LocalDateTime to) {
        LocalDateTime resolvedTo = to != null ? to : LocalDateTime.now();
        LocalDateTime resolvedFrom = from != null ? from : resolvedTo.minusDays(DEFAULT_REPORT_DAYS);
        if (resolvedFrom.isAfter(resolvedTo)) {
            throw new BusinessException("From date must be before to date");
        }
        if (Duration.between(resolvedFrom, resolvedTo).toDays() > MAX_REPORT_DAYS) {
            throw new BusinessException("Accounting date range cannot exceed " + MAX_REPORT_DAYS + " days");
        }
        return new DateRange(resolvedFrom, resolvedTo);
    }

    private UUID requireTenant() {
        UUID clientId = TenantContext.getCurrentTenant();
        if (clientId == null) {
            throw new BusinessException("Client context is required");
        }
        return clientId;
    }

    private UUID resolveOrg(UUID requestedOrgId) {
        if (requestedOrgId != null) {
            return requestedOrgId;
        }
        UUID currentOrgId = TenantContext.getCurrentOrg();
        if (currentOrgId != null) {
            return currentOrgId;
        }
        if (SecurityUtils.isSuperAdmin() || SecurityUtils.hasRole("ADMIN")) {
            return null;
        }
        return branchContext.requireWriteOrgId(requestedOrgId);
    }

    private String normalizeCode(String value) {
        return requireText(value, "Account code is required").toUpperCase(Locale.ROOT);
    }

    private String requireText(String value, String message) {
        String trimmed = trimToNull(value);
        if (trimmed == null) {
            throw new BusinessException(message);
        }
        return trimmed;
    }

    private AccountType requireAccountType(AccountType accountType) {
        if (accountType == null) {
            throw new BusinessException("Account type is required");
        }
        return accountType;
    }

    private String requireJournalNo(String entryNo) {
        String normalized = trimToNull(entryNo);
        return normalized != null ? normalized : "JE-" + System.currentTimeMillis();
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private String isActiveFlag(String value) {
        return "N".equalsIgnoreCase(value) ? "N" : "Y";
    }

    private BigDecimal money(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private static final class DateRange {
        private final LocalDateTime from;
        private final LocalDateTime to;

        private DateRange(LocalDateTime from, LocalDateTime to) {
            this.from = from;
            this.to = to;
        }
    }

    private record AccountMovement(BigDecimal debit, BigDecimal credit) {
    }
}
