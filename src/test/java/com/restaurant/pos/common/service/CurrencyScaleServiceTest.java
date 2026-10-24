package com.restaurant.pos.common.service;

import com.restaurant.pos.purchasing.domain.Currency;
import com.restaurant.pos.purchasing.repository.CurrencyRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CurrencyScaleServiceTest {

    private final UUID clientId = UUID.randomUUID();
    private final UUID orgId = UUID.randomUUID();

    private Currency currency(int decimalPlaces) {
        return Currency.builder().code("XXX").symbol("X").name("Test").decimalPlaces(decimalPlaces).build();
    }

    @Test
    void usesOrgDefaultDecimalPlacesWhenPresent() {
        CurrencyRepository repo = mock(CurrencyRepository.class);
        when(repo.findByClientIdAndOrgIdAndIsDefaultTrue(eq(clientId), eq(orgId)))
                .thenReturn(List.of(currency(3)));

        assertThat(new CurrencyScaleService(repo).decimalPlaces(clientId, orgId)).isEqualTo(3);
    }

    @Test
    void fallsBackToClientDefaultWhenOrgHasNone() {
        CurrencyRepository repo = mock(CurrencyRepository.class);
        when(repo.findByClientIdAndOrgIdAndIsDefaultTrue(eq(clientId), eq(orgId))).thenReturn(List.of());
        when(repo.findByClientIdAndIsDefaultTrue(eq(clientId))).thenReturn(List.of(currency(0)));

        assertThat(new CurrencyScaleService(repo).decimalPlaces(clientId, orgId)).isEqualTo(0);
    }

    @Test
    void returnsTwoWhenNoDefaultCurrencyIsConfigured() {
        CurrencyRepository repo = mock(CurrencyRepository.class);
        when(repo.findByClientIdAndOrgIdAndIsDefaultTrue(any(), any())).thenReturn(List.of());
        when(repo.findByClientIdAndIsDefaultTrue(any())).thenReturn(List.of());

        assertThat(new CurrencyScaleService(repo).decimalPlaces(clientId, orgId))
                .isEqualTo(CurrencyScaleService.FALLBACK_DECIMAL_PLACES);
    }

    @Test
    void returnsTwoWhenClientIsUnknown() {
        CurrencyRepository repo = mock(CurrencyRepository.class);

        assertThat(new CurrencyScaleService(repo).decimalPlaces(null, orgId)).isEqualTo(2);
    }
}
