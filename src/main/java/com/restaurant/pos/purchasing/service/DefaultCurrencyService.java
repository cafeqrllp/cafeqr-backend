package com.restaurant.pos.purchasing.service;

import com.restaurant.pos.purchasing.domain.Currency;
import com.restaurant.pos.purchasing.repository.CurrencyRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Gives every new branch (organization) a default currency (INR, symbol "Rs", 2 decimal places)
 * when it has none. Branches that already have a currency are left unchanged.
 */
@Service
@RequiredArgsConstructor
public class DefaultCurrencyService {

    static final String DEFAULT_CODE = "INR";
    static final String DEFAULT_SYMBOL = "Rs";
    static final String DEFAULT_NAME = "Indian Rupee";
    static final int DEFAULT_DECIMAL_PLACES = 2;

    private final CurrencyRepository currencyRepository;

    @Transactional
    public void ensureDefaultCurrency(UUID clientId, UUID orgId) {
        if (clientId == null || orgId == null || currencyRepository.existsByClientIdAndOrgId(clientId, orgId)) {
            return;
        }
        Currency currency = Currency.builder()
                .code(DEFAULT_CODE)
                .symbol(DEFAULT_SYMBOL)
                .name(DEFAULT_NAME)
                .decimalPlaces(DEFAULT_DECIMAL_PLACES)
                .isDefault(true)
                .isactive("Y")
                .build();
        currency.setClientId(clientId);
        currency.setOrgId(orgId);
        currencyRepository.save(currency);
    }
}
