package com.restaurant.pos.client.service;

import com.restaurant.pos.client.domain.Client;
import com.restaurant.pos.client.repository.ClientRepository;
import com.restaurant.pos.common.exception.ResourceNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ClientService {

    private final ClientRepository clientRepository;
    private final com.restaurant.pos.common.context.TimezoneResolver timezoneResolver;

    public List<Client> getAllClients() {
        return clientRepository.findAll();
    }

    public Client getClientById(UUID id) {
        return clientRepository.findById(java.util.Objects.requireNonNull(id))
                .orElseThrow(() -> new ResourceNotFoundException("Client not found with id: " + id));
    }

    public Client getClientByEmail(String email) {
        return clientRepository.findByEmail(email)
                .orElseThrow(() -> new ResourceNotFoundException("Client not found with email: " + email));
    }

    @Transactional
    public Client updateClient(UUID id, Client clientDetails) {
        Client client = getClientById(id);
        client.setName(clientDetails.getName());
        client.setPhone(clientDetails.getPhone());
        client.setCountry(clientDetails.getCountry());
        client.setPosType(clientDetails.getPosType());
        
        // Multi-country / Registration fields
        client.setAddress(clientDetails.getAddress());
        client.setGstNumber(clientDetails.getGstNumber());
        client.setFssaiNumber(clientDetails.getFssaiNumber());
        client.setWebsite(clientDetails.getWebsite());
        client.setCurrency(clientDetails.getCurrency());
        
        // Branding & Operational
        client.setLogoUrl(clientDetails.getLogoUrl());
        client.setBannerUrl(clientDetails.getBannerUrl());
        client.setBrandColor(clientDetails.getBrandColor());
        client.setTimezone(clientDetails.getTimezone());
        client.setPrimaryLanguage(clientDetails.getPrimaryLanguage());

        // Slug management
        if (clientDetails.getSlug() != null && !clientDetails.getSlug().isBlank()) {
            String sanitized = sanitizeSlug(clientDetails.getSlug());
            if (clientRepository.existsBySlugIgnoreCaseAndIdNot(sanitized, id)) {
                throw new com.restaurant.pos.common.exception.BusinessException("The store handle '" + sanitized + "' is already taken by another business. Please choose a different handle.");
            }
            client.setSlug(sanitized);
        } else if (client.getSlug() == null || client.getSlug().isBlank()) {
            client.setSlug(generateUniqueClientSlug(client.getName(), id));
        }

        // Social & Engagement
        client.setInstagramUrl(clientDetails.getInstagramUrl());
        client.setFacebookUrl(clientDetails.getFacebookUrl());
        client.setWhatsappNumber(clientDetails.getWhatsappNumber());

        // Location & Finance
        client.setGoogleMapsUrl(clientDetails.getGoogleMapsUrl());
        client.setPinCode(clientDetails.getPinCode());
        client.setBankName(clientDetails.getBankName());
        client.setAccountNumber(clientDetails.getAccountNumber());
        client.setIfscCode(clientDetails.getIfscCode());
        
        Client saved = clientRepository.save(client);
        timezoneResolver.evictCache(saved.getId(), null);
        return saved;
    }

    @Transactional
    public Client createClient(Client client) {
        if (client.getSlug() == null || client.getSlug().isBlank()) {
            client.setSlug(generateUniqueClientSlug(client.getName(), client.getId()));
        } else {
            client.setSlug(sanitizeSlug(client.getSlug()));
        }
        return clientRepository.save(java.util.Objects.requireNonNull(client));
    }

    public static String sanitizeSlug(String input) {
        if (input == null) return "store";
        String s = input.trim().toLowerCase().replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
        return s.isBlank() ? "store" : s;
    }

    private String generateUniqueClientSlug(String name, UUID excludeId) {
        String base = sanitizeSlug(name);
        String candidate = base;
        int count = 1;
        while (clientRepository.existsBySlugIgnoreCase(candidate)) {
            count++;
            candidate = base + "-" + count;
        }
        return candidate;
    }
}
