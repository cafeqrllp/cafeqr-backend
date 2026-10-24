package com.restaurant.pos.product.service;

import com.restaurant.pos.product.domain.Uom;
import com.restaurant.pos.product.repository.UomRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Gives every client a default UOM ("Each", symbol "Ea", 0 decimal places)
 * when it has never set up any UOM. Clients that already have UOMs are left unchanged.
 */
@Service
@RequiredArgsConstructor
public class DefaultUomService {

    static final String DEFAULT_NAME = "Each";
    static final String DEFAULT_SYMBOL = "Ea";
    static final int DEFAULT_PRECISION = 0;

    private final UomRepository uomRepository;

    @Transactional
    public void ensureDefaultUom(UUID clientId) {
        if (clientId == null || uomRepository.existsByClientId(clientId)) {
            return;
        }
        uomRepository.save(Uom.builder()
                .name(DEFAULT_NAME)
                .shortName(DEFAULT_SYMBOL)
                .uomPrecision(DEFAULT_PRECISION)
                .isDefault(true)
                .isActive(true)
                .clientId(clientId)
                .build());
    }
}
