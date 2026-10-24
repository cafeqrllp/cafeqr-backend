package com.restaurant.pos.common.service;

import com.restaurant.pos.purchasing.domain.Currency;
import com.restaurant.pos.purchasing.repository.CurrencyRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Money scale (decimal places) of the default currency in Currency Master.
 *
 * <p>Every money amount that is rounded or stored by the backend uses this scale, so a currency
 * with 0 places (JPY, KRW) or 3 places (KWD, BHD, OMR, JOD) is rounded correctly. Quantities,
 * hours, percentages and rates keep their own scales.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CurrencyScaleService {

    public static final int FALLBACK_DECIMAL_PLACES = 2;

    private final CurrencyRepository currencyRepository;

    /**
     * Decimal places of the default currency for the client/org (org default first, then client default).
     * Returns {@link #FALLBACK_DECIMAL_PLACES} when no default is configured.
     */
    public int decimalPlaces(UUID clientId, UUID orgId) {
        if (clientId == null) {
            return FALLBACK_DECIMAL_PLACES;
        }
        try {
            Optional<Currency> defaultCurrency = orgId != null
                    ? currencyRepository.findByClientIdAndOrgIdAndIsDefaultTrue(clientId, orgId).stream().findFirst()
                    : Optional.empty();
            if (defaultCurrency.isEmpty()) {
                defaultCurrency = currencyRepository.findByClientIdAndIsDefaultTrue(clientId).stream().findFirst();
            }
            return defaultCurrency.map(Currency::getDecimalPlaces)
                    .filter(Objects::nonNull)
                    .orElse(FALLBACK_DECIMAL_PLACES);
        } catch (RuntimeException e) {
            log.warn("Failed to resolve currency decimal places for client {}, org {}; using {}",
                    clientId, orgId, FALLBACK_DECIMAL_PLACES, e);
            return FALLBACK_DECIMAL_PLACES;
        }
    }
}
