package com.restaurant.pos.accounting.service;

import com.restaurant.pos.accounting.domain.*;
import com.restaurant.pos.accounting.dto.AccountingMappingsDto;
import com.restaurant.pos.accounting.repository.*;
import com.restaurant.pos.common.exception.BusinessException;
import com.restaurant.pos.common.service.BranchContextService;
import com.restaurant.pos.common.tenant.TenantContext;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Service
@RequiredArgsConstructor
public class AccountingDefaultsService {

    public static final String CASH = "CASH";
    public static final String BANK_UPI_CLEARING = "BANK_UPI_CLEARING";
    public static final String ACCOUNTS_RECEIVABLE = "ACCOUNTS_RECEIVABLE";
    public static final String ACCOUNTS_PAYABLE = "ACCOUNTS_PAYABLE";
    public static final String SALES_REVENUE = "SALES_REVENUE";
    public static final String PURCHASE_COGS = "PURCHASE_COGS";
    public static final String INVENTORY_ASSET = "INVENTORY_ASSET";
    public static final String INPUT_TAX = "INPUT_TAX";
    public static final String OUTPUT_TAX = "OUTPUT_TAX";
    public static final String OPERATING_EXPENSES = "OPERATING_EXPENSES";
    public static final String DISCOUNT_ALLOWED = "DISCOUNT_ALLOWED";
    public static final String ROUND_OFF = "ROUND_OFF";
    public static final String STOCK_ADJUSTMENT_GAIN_LOSS = "STOCK_ADJUSTMENT_GAIN_LOSS";

    private static final List<AccountTemplate> ACCOUNT_TEMPLATES = List.of(
            new AccountTemplate(CASH, "SYS-1000", "Cash in Hand", AccountType.ASSET, "Cash", true, false),
            new AccountTemplate(BANK_UPI_CLEARING, "SYS-1010", "Bank / UPI Clearing", AccountType.ASSET, "Bank", false, true),
            new AccountTemplate(ACCOUNTS_RECEIVABLE, "SYS-1100", "Accounts Receivable", AccountType.ASSET, "Receivable", false, false),
            new AccountTemplate(INVENTORY_ASSET, "SYS-1200", "Inventory Asset", AccountType.ASSET, "Inventory", false, false),
            new AccountTemplate(INPUT_TAX, "SYS-1300", "Input Tax", AccountType.ASSET, "Tax", false, false),
            new AccountTemplate(ACCOUNTS_PAYABLE, "SYS-2000", "Accounts Payable", AccountType.LIABILITY, "Payable", false, false),
            new AccountTemplate(OUTPUT_TAX, "SYS-2100", "Output Tax", AccountType.LIABILITY, "Tax", false, false),
            new AccountTemplate(SALES_REVENUE, "SYS-4000", "Sales Revenue", AccountType.INCOME, "Sales", false, false),
            new AccountTemplate(PURCHASE_COGS, "SYS-5000", "Purchase / Cost of Goods Sold", AccountType.EXPENSE, "COGS", false, false),
            new AccountTemplate(OPERATING_EXPENSES, "SYS-5100", "Operating Expenses", AccountType.EXPENSE, "Expense", false, false),
            new AccountTemplate(DISCOUNT_ALLOWED, "SYS-5200", "Discount Allowed", AccountType.EXPENSE, "Discount", false, false),
            new AccountTemplate(ROUND_OFF, "SYS-5300", "Round Off", AccountType.EXPENSE, "Round Off", false, false),
            new AccountTemplate(STOCK_ADJUSTMENT_GAIN_LOSS, "SYS-5400", "Stock Adjustment Gain/Loss", AccountType.EXPENSE, "Stock Adjustment", false, false)
    );

    private static final List<String> PAYMENT_METHODS = List.of("CASH", "ONLINE", "UPI", "CARD", "BANK", "CHEQUE", "MIXED");

    private final AccountingAccountRepository accountRepository;
    private final AccountingAccountMappingRepository mappingRepository;
    private final AccountingPaymentMethodMappingRepository paymentMappingRepository;
    private final BranchContextService branchContext;
    private final Map<String, Boolean> ensuredDefaults = new ConcurrentHashMap<>();

    @Transactional
    public List<AccountingAccount> ensureDefaultAccounts() {
        return ensureDefaultAccounts(TenantContext.getCurrentOrg());
    }

    @Transactional
    public List<AccountingAccount> ensureDefaultAccounts(UUID orgId) {
        UUID clientId = requireClient();
        UUID effectiveOrgId = orgId != null ? orgId : TenantContext.getCurrentOrg();
        if (effectiveOrgId == null) {
            return Collections.emptyList();
        }
        Map<String, AccountingAccount> accountsByKey = new LinkedHashMap<>();

        for (AccountTemplate template : ACCOUNT_TEMPLATES) {
            AccountingAccount account = accountRepository
                    .findByClientIdAndOrgIdAndSystemKeyIgnoreCase(clientId, effectiveOrgId, template.getSystemKey())
                    .or(() -> accountRepository.findByClientIdAndOrgIdAndCodeIgnoreCase(clientId, effectiveOrgId, template.getCode())
                            .map(existingAccount -> recoverSystemAccount(existingAccount, template)))
                    .orElseGet(() -> createSystemAccount(clientId, effectiveOrgId, template));
            accountsByKey.put(template.getSystemKey(), account);
        }

        for (Map.Entry<String, AccountingAccount> entry : accountsByKey.entrySet()) {
            upsertAccountMapping(clientId, effectiveOrgId, entry.getKey(), entry.getValue().getId(), "System default mapping");
        }

        upsertPaymentMapping(clientId, effectiveOrgId, "CASH", accountsByKey.get(CASH).getId());
        UUID bankAccountId = accountsByKey.get(BANK_UPI_CLEARING).getId();
        for (String method : PAYMENT_METHODS) {
            if (!"CASH".equals(method)) {
                upsertPaymentMapping(clientId, effectiveOrgId, method, bankAccountId);
            }
        }

        ensuredDefaults.put(defaultsKey(clientId, effectiveOrgId), true);
        return new ArrayList<>(accountsByKey.values());
    }

    @Transactional(readOnly = true)
    public AccountingMappingsDto getMappings() {
        UUID clientId = requireClient();
        UUID orgId = TenantContext.getCurrentOrg();
        AccountingMappingsDto dto = AccountingMappingsDto.builder().build();
        mappingRepository.findByClientIdAndOrgIdAndIsactiveOrderByMappingKeyAsc(clientId, orgId, "Y")
                .forEach(mapping -> dto.getAccountMappings().put(mapping.getMappingKey(), mapping.getAccountId()));
        paymentMappingRepository.findByClientIdAndOrgIdAndIsactiveOrderByPaymentMethodAsc(clientId, orgId, "Y")
                .forEach(mapping -> dto.getPaymentMethodMappings().put(mapping.getPaymentMethod(), mapping.getAccountId()));
        return dto;
    }

    @Transactional
    public AccountingMappingsDto updateMappings(AccountingMappingsDto dto) {
        ensureDefaultAccounts();
        UUID clientId = requireClient();
        UUID orgId = TenantContext.getCurrentOrg();
        if (dto.getAccountMappings() != null) {
            dto.getAccountMappings().forEach((key, accountId) -> {
                validateAccount(clientId, orgId, accountId);
                upsertAccountMapping(clientId, orgId, normalizeKey(key), accountId, "Admin override");
            });
        }
        if (dto.getPaymentMethodMappings() != null) {
            dto.getPaymentMethodMappings().forEach((method, accountId) -> {
                validateAccount(clientId, orgId, accountId);
                upsertPaymentMapping(clientId, orgId, normalizeKey(method), accountId);
            });
        }
        return getMappings();
    }

    @Transactional
    public AccountingAccount resolveAccount(String mappingKey) {
        UUID clientId = requireClient();
        UUID orgId = TenantContext.getCurrentOrg();
        String key = normalizeKey(mappingKey);
        ensureDefaultsIfMissing(clientId, orgId, key);
        Optional<AccountingAccountMapping> override = mappingRepository.findByClientIdAndOrgIdAndMappingKeyIgnoreCase(clientId, orgId, key)
                .filter(mapping -> "Y".equalsIgnoreCase(mapping.getIsactive()));
        if (override.isPresent()) {
            return accountRepository.findByIdAndClientIdAndOrgId(override.get().getAccountId(), clientId, orgId)
                    .orElseThrow(() -> new BusinessException("Mapped accounting account is invalid: " + key));
        }
        return accountRepository.findByClientIdAndOrgIdAndSystemKeyIgnoreCase(clientId, orgId, key)
                .orElseThrow(() -> new BusinessException("Accounting account is not configured: " + key));
    }

    @Transactional
    public AccountingAccount resolvePaymentAccount(String paymentMethod) {
        UUID clientId = requireClient();
        UUID orgId = TenantContext.getCurrentOrg();
        String method = normalizePaymentMethod(paymentMethod);
        boolean isCash = "CASH".equalsIgnoreCase(method);
        ensureDefaultsIfMissing(clientId, orgId, isCash ? CASH : BANK_UPI_CLEARING);
        Optional<AccountingPaymentMethodMapping> override = paymentMappingRepository
                .findByClientIdAndOrgIdAndPaymentMethodIgnoreCase(clientId, orgId, method)
                .filter(mapping -> "Y".equalsIgnoreCase(mapping.getIsactive()));
        if (override.isPresent()) {
            return accountRepository.findByIdAndClientIdAndOrgId(override.get().getAccountId(), clientId, orgId)
                    .orElseThrow(() -> new BusinessException("Mapped payment account is invalid: " + method));
        }
        return resolveAccount(isCash ? CASH : BANK_UPI_CLEARING);
    }

    public String normalizePaymentMethod(String paymentMethod) {
        if (paymentMethod == null || paymentMethod.isBlank()) {
            return "CASH";
        }
        return normalizeKey(paymentMethod);
    }

    private void ensureDefaultsIfMissing(UUID clientId, UUID orgId, String requiredSystemKey) {
        UUID effectiveOrgId = orgId != null ? orgId : TenantContext.getCurrentOrg();
        if (effectiveOrgId == null) {
            return;
        }
        String cacheKey = defaultsKey(clientId, effectiveOrgId);
        if (Boolean.TRUE.equals(ensuredDefaults.get(cacheKey))
                && accountRepository.findByClientIdAndOrgIdAndSystemKeyIgnoreCase(clientId, effectiveOrgId, requiredSystemKey).isPresent()) {
            return;
        }
        if (accountRepository.findByClientIdAndOrgIdAndSystemKeyIgnoreCase(clientId, effectiveOrgId, requiredSystemKey).isPresent()) {
            ensuredDefaults.put(cacheKey, true);
            return;
        }
        ensureDefaultAccounts(effectiveOrgId);
    }

    private String defaultsKey(UUID clientId, UUID orgId) {
        return clientId + ":" + (orgId != null ? orgId : "GLOBAL");
    }

    private AccountingAccount createSystemAccount(UUID clientId, UUID orgId, AccountTemplate template) {
        AccountingAccount account = AccountingAccount.builder()
                .code(template.getCode())
                .name(template.getName())
                .accountType(template.getAccountType())
                .accountSubType(template.getSubType())
                .systemKey(template.getSystemKey())
                .systemAccount(true)
                .cashAccount(template.isCashAccount())
                .bankAccount(template.isBankAccount())
                .openingBalance(BigDecimal.ZERO)
                .currentBalance(BigDecimal.ZERO)
                .isactive("Y")
                .build();
        account.setClientId(clientId);
        account.setOrgId(orgId);
        return accountRepository.save(account);
    }

    private AccountingAccount recoverSystemAccount(AccountingAccount account, AccountTemplate template) {
        account.setCode(template.getCode());
        account.setName(template.getName());
        account.setAccountType(template.getAccountType());
        account.setAccountSubType(template.getSubType());
        account.setSystemKey(template.getSystemKey());
        account.setSystemAccount(true);
        account.setCashAccount(template.isCashAccount());
        account.setBankAccount(template.isBankAccount());
        if (account.getOpeningBalance() == null) {
            account.setOpeningBalance(BigDecimal.ZERO);
        }
        if (account.getCurrentBalance() == null) {
            account.setCurrentBalance(account.getOpeningBalance());
        }
        if (account.getIsactive() == null || account.getIsactive().isBlank()) {
            account.setIsactive("Y");
        }
        return accountRepository.save(account);
    }

    private void upsertAccountMapping(UUID clientId, UUID orgId, String mappingKey, UUID accountId, String description) {
        String key = normalizeKey(mappingKey);
        AccountingAccountMapping mapping = mappingRepository.findByClientIdAndOrgIdAndMappingKeyIgnoreCase(clientId, orgId, key)
                .orElseGet(() -> {
                    AccountingAccountMapping created = AccountingAccountMapping.builder()
                            .mappingKey(key)
                            .isactive("Y")
                            .build();
                    created.setClientId(clientId);
                    created.setOrgId(orgId);
                    return created;
                });
        mapping.setAccountId(accountId);
        mapping.setDescription(description);
        mapping.setIsactive("Y");
        mappingRepository.save(mapping);
    }

    private void upsertPaymentMapping(UUID clientId, UUID orgId, String paymentMethod, UUID accountId) {
        String method = normalizeKey(paymentMethod);
        AccountingPaymentMethodMapping mapping = paymentMappingRepository
                .findByClientIdAndOrgIdAndPaymentMethodIgnoreCase(clientId, orgId, method)
                .orElseGet(() -> {
                    AccountingPaymentMethodMapping created = AccountingPaymentMethodMapping.builder()
                            .paymentMethod(method)
                            .isactive("Y")
                            .build();
                    created.setClientId(clientId);
                    created.setOrgId(orgId);
                    return created;
                });
        mapping.setAccountId(accountId);
        mapping.setDescription("Payment method account mapping");
        mapping.setIsactive("Y");
        paymentMappingRepository.save(mapping);
    }

    private void validateAccount(UUID clientId, UUID orgId, UUID accountId) {
        if (accountId == null || accountRepository.findByIdAndClientIdAndOrgId(accountId, clientId, orgId).isEmpty()) {
            throw new BusinessException("Mapped accounting account is invalid");
        }
    }

    private UUID requireClient() {
        UUID clientId = TenantContext.getCurrentTenant();
        if (clientId == null) {
            throw new BusinessException("Client context is required");
        }
        return clientId;
    }

    private String normalizeKey(String value) {
        if (value == null || value.isBlank()) {
            throw new BusinessException("Accounting mapping key is required");
        }
        return value.trim().toUpperCase(Locale.ROOT).replace(' ', '_');
    }

    @Getter
    @RequiredArgsConstructor
    private static final class AccountTemplate {
        private final String systemKey;
        private final String code;
        private final String name;
        private final AccountType accountType;
        private final String subType;
        private final boolean cashAccount;
        private final boolean bankAccount;
    }
}
