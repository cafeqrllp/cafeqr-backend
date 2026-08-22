package com.restaurant.pos.client.service;

import com.restaurant.pos.client.domain.Organization;
import com.restaurant.pos.client.domain.Client;
import com.restaurant.pos.client.repository.OrganizationRepository;
import com.restaurant.pos.client.repository.ClientRepository;
import com.restaurant.pos.common.exception.ResourceNotFoundException;
import com.restaurant.pos.common.tenant.TenantContext;
import com.restaurant.pos.paymenttype.domain.PaymentType;
import com.restaurant.pos.paymenttype.repository.PaymentTypeRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class OrganizationService {

    private final OrganizationRepository repository;
    private final ClientRepository clientRepository;
    private final PaymentTypeRepository paymentTypeRepository;
    private final com.restaurant.pos.common.context.TimezoneResolver timezoneResolver;

    public List<Organization> getMyOrganizations() {
        UUID tenantId = TenantContext.getCurrentTenant();
        if (tenantId == null) {
            log.warn("Skipping organization lookup because tenant context is missing.");
            return List.of();
        }

        log.debug("Fetching organizations for Client ID: {}", tenantId);
        List<Organization> orgs = repository.findAllByClientId(tenantId);
        log.debug("Found {} organizations for Client ID: {}", orgs.size(), tenantId);
        return orgs;
    }

    public Organization getOrganizationById(UUID id) {
        return repository.findByIdAndClientId(id, TenantContext.getCurrentTenant())
                .orElseThrow(() -> new ResourceNotFoundException("Organization not found"));
    }

    @Transactional
    public Organization createOrganization(Organization organization) {
        log.info("Creating new organization: {}", organization);
        UUID clientId = TenantContext.getCurrentTenant();
        organization.setClientId(clientId);
        organization.setIsactive("Y");

        // Set default orgCode and slug if not provided
        String rawSlug = ClientService.sanitizeSlug(organization.getName());
        if (organization.getSlug() == null || organization.getSlug().isBlank()) {
            organization.setSlug(rawSlug);
        } else {
            organization.setSlug(ClientService.sanitizeSlug(organization.getSlug()));
        }

        if (organization.getOrgCode() == null || organization.getOrgCode().isBlank()) {
            String code = rawSlug + "-" + UUID.randomUUID().toString().substring(0, 4);
            organization.setOrgCode(code);
            log.info("Generated default orgCode: {}", code);
        }

        Client client = clientRepository.findById(java.util.Objects.requireNonNull(clientId))
                .orElseThrow(() -> new ResourceNotFoundException("Client not found for ID: " + clientId));
        organization.setClient(client);

        if (organization.getPosType() == null || organization.getPosType().isBlank()) {
            organization.setPosType(client.getPosType());
        }

        Organization saved = repository.save(organization);
        seedDefaultPaymentTypes(saved);
        return saved;
    }

    @Transactional
    public Organization updateOrganization(UUID id, Organization details) {
        Organization organization = getOrganizationById(id);
        organization.setName(details.getName());
        organization.setOrgCode(details.getOrgCode());
        
        if (details.getSlug() != null && !details.getSlug().isBlank()) {
            String sanitizedBranchSlug = ClientService.sanitizeSlug(details.getSlug());
            if (repository.existsByClientIdAndSlugIgnoreCaseAndIdNot(organization.getClientId(), sanitizedBranchSlug, id)) {
                throw new com.restaurant.pos.common.exception.BusinessException("The branch slug '" + sanitizedBranchSlug + "' is already in use by another branch in your organization. Please choose a different branch slug.");
            }
            organization.setSlug(sanitizedBranchSlug);
        } else if (organization.getSlug() == null || organization.getSlug().isBlank()) {
            organization.setSlug(ClientService.sanitizeSlug(details.getName()));
        }

        organization.setAddress(details.getAddress());
        organization.setPhone(details.getPhone());
        organization.setEmail(details.getEmail());
        organization.setGstin(details.getGstin());
        organization.setLogoUrl(details.getLogoUrl());
        organization.setBannerUrl(details.getBannerUrl());
        organization.setGoogleMapsUrl(details.getGoogleMapsUrl());
        organization.setPinCode(details.getPinCode());
        organization.setLatitude(details.getLatitude());
        organization.setLongitude(details.getLongitude());
        organization.setDeliveryRadiusKm(details.getDeliveryRadiusKm());
        organization.setBranchCode(details.getBranchCode());
        organization.setTimezone(details.getTimezone());
        organization.setPosType(details.getPosType());
        
        if (details.getIsactive() != null) {
            organization.setIsactive(details.getIsactive());
        }

        Organization saved = repository.save(organization);
        timezoneResolver.evictCache(saved.getClientId(), saved.getId());
        return saved;
    }

    @Transactional
    public void deleteOrganization(UUID id) {
        // Soft Delete
        Organization organization = repository.findByIdAndClientId(id, TenantContext.getCurrentTenant())
                .orElseThrow(() -> new ResourceNotFoundException("Organization not found"));
        organization.setIsactive("N");
        repository.save(organization);
    }

    /**
     * Seeds 6 default payment types for a newly created branch.
     * Called automatically inside createOrganization().
     */
    private void seedDefaultPaymentTypes(Organization org) {
        UUID clientId = org.getClientId();
        UUID orgId = org.getId();

        record Seed(String display, String paymentType, String sales, String purchase, String expense, int sort, boolean isDefault) {}

        List<Seed> defaults = List.of(
            new Seed("Cash",   "OTHERS", "Y", "Y", "Y",                        1, true),
            new Seed("Online", "OTHERS", "Y", "Y", "Y",                        2, false),
            new Seed("UPI",    "OTHERS", "Y", "Y", "Y",                        3, false),
            new Seed("Card",   "OTHERS", "Y", "Y", "Y",                        4, false),
            new Seed("Mixed",  "OTHERS", "Y", "Y", "Y",                        5, false),
            new Seed("Credit", "CREDIT", "Y", "N", "N",                      6, false)
        );

        for (Seed s : defaults) {
            boolean exists = paymentTypeRepository.existsByClientIdAndOrgIdAndDisplayName(clientId, orgId, s.display());
            if (!exists) {
                PaymentType pt = PaymentType.builder()
                        .displayName(s.display())
                        .paymentType(s.paymentType())
                        .sales(s.sales())
                        .purchase(s.purchase())
                        .expense(s.expense())
                        .sortOrder(s.sort())
                        .isDefault(s.isDefault())
                        .isactive("Y")
                        .build();
                pt.setClientId(clientId);
                pt.setOrgId(orgId);
                pt.setCreatedBy("SYSTEM");
                paymentTypeRepository.save(pt);
            }
        }
        log.info("Seeded {} default payment types for new branch '{}'", defaults.size(), org.getName());
    }
}
