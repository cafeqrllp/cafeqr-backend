package com.restaurant.pos.founder.lead.service;

import com.restaurant.pos.auth.domain.User;
import com.restaurant.pos.auth.repository.UserRepository;
import com.restaurant.pos.client.domain.Client;
import com.restaurant.pos.client.domain.Organization;
import com.restaurant.pos.client.repository.ClientRepository;
import com.restaurant.pos.client.repository.OrganizationRepository;
import com.restaurant.pos.founder.lead.domain.FounderLead;
import com.restaurant.pos.founder.lead.domain.FounderLeadActivity;
import com.restaurant.pos.founder.lead.dto.*;
import com.restaurant.pos.founder.lead.repository.FounderLeadActivityRepository;
import com.restaurant.pos.founder.lead.repository.FounderLeadRepository;
import com.restaurant.pos.subscription.domain.ClientSubscriptionModule;
import com.restaurant.pos.subscription.domain.ModuleName;
import com.restaurant.pos.subscription.repository.ClientSubscriptionModuleRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class FounderLeadService {

    private final FounderLeadRepository leadRepository;
    private final FounderLeadActivityRepository activityRepository;
    private final OrganizationRepository organizationRepository;
    private final ClientRepository clientRepository;
    private final UserRepository userRepository;
    private final ClientSubscriptionModuleRepository moduleRepository;

    // ── Phone Normalization Utility ──────────────────────────────────────────
    public static String normalizePhone(String raw) {
        if (raw == null) return "";
        String digits = raw.replaceAll("[^0-9]", "");
        // Remove 91 prefix if 12 digits (Indian mobile)
        if (digits.length() == 12 && digits.startsWith("91")) {
            digits = digits.substring(2);
        }
        // Remove leading 0 if 11 digits
        if (digits.length() == 11 && digits.startsWith("0")) {
            digits = digits.substring(1);
        }
        return digits;
    }

    public static List<String> generatePhoneSearchVariants(String raw) {
        String clean = normalizePhone(raw);
        if (clean.isEmpty()) return List.of();
        Set<String> variants = new LinkedHashSet<>();
        variants.add(clean);
        variants.add("+91" + clean);
        variants.add("91" + clean);
        variants.add("0" + clean);
        if (raw != null && !raw.trim().isEmpty()) {
            variants.add(raw.trim());
        }
        return new ArrayList<>(variants);
    }

    // ── Multi-Contact Merchant Lookup (Branch-First Fallback) ─────────────────
    public MerchantLookupResponse resolveMerchantByPhone(String rawPhone) {
        if (rawPhone == null || rawPhone.isBlank()) {
            return MerchantLookupResponse.builder().matched(false).matchedType("NONE").build();
        }

        List<String> variants = generatePhoneSearchVariants(rawPhone);
        LocalDateTime now = LocalDateTime.now();

        // 1. Try Branch (Organization) first
        for (String phoneVar : variants) {
            Optional<Organization> orgOpt = organizationRepository.findFirstByAnyPhone(phoneVar);
            if (orgOpt.isEmpty()) {
                orgOpt = organizationRepository.findFirstByPhone(phoneVar);
            }
            if (orgOpt.isEmpty()) {
                orgOpt = organizationRepository.findFirstByWhatsappNumber(phoneVar);
            }

            if (orgOpt.isPresent()) {
                Organization org = orgOpt.get();
                Client client = null;
                if (org.getClientId() != null) {
                    client = clientRepository.findById(org.getClientId()).orElse(null);
                }

                boolean isSubActive = org.getSubscriptionExpiryDate() != null && org.getSubscriptionExpiryDate().isAfter(now);
                List<ClientSubscriptionModule> orgModules = moduleRepository.findByOrgId(org.getId());
                List<String> activeMods = orgModules.stream()
                        .filter(m -> "ACTIVE".equalsIgnoreCase(m.getStatus()) && (m.getExpiryDate() == null || m.getExpiryDate().isAfter(now)))
                        .map(m -> m.getModuleName().name())
                        .collect(Collectors.toList());

                return MerchantLookupResponse.builder()
                        .matched(true)
                        .matchedType("BRANCH")
                        .orgId(org.getId())
                        .clientId(org.getClientId())
                        .businessName(client != null && client.getName() != null ? client.getName() : org.getName())
                        .branchName(org.getName())
                        .branchCode(org.getBranchCode())
                        .contactPerson(client != null ? client.getOwnerName() : org.getName())
                        .phone(org.getPhone())
                        .whatsappNumber(org.getWhatsappNumber())
                        .email(org.getEmail())
                        .city(org.getAddress())
                        .subscriptionStatus(org.getSubscriptionStatus())
                        .subscriptionExpiryDate(org.getSubscriptionExpiryDate())
                        .isSubscriptionActive(isSubActive)
                        .activeModules(activeMods)
                        .build();
            }
        }

        // 2. Try Client fallback
        for (String phoneVar : variants) {
            Optional<Client> clientOpt = clientRepository.findFirstByAnyPhone(phoneVar);
            if (clientOpt.isEmpty()) {
                clientOpt = clientRepository.findFirstByPhone(phoneVar);
            }
            if (clientOpt.isEmpty()) {
                clientOpt = clientRepository.findFirstByWhatsappNumber(phoneVar);
            }

            if (clientOpt.isPresent()) {
                Client client = clientOpt.get();
                boolean isSubActive = client.getSubscriptionExpiryDate() != null && client.getSubscriptionExpiryDate().isAfter(now);
                List<ClientSubscriptionModule> clientModules = moduleRepository.findByClientId(client.getId());
                List<String> activeMods = clientModules.stream()
                        .filter(m -> "ACTIVE".equalsIgnoreCase(m.getStatus()) && (m.getExpiryDate() == null || m.getExpiryDate().isAfter(now)))
                        .map(m -> m.getModuleName().name())
                        .collect(Collectors.toList());

                return MerchantLookupResponse.builder()
                        .matched(true)
                        .matchedType("CLIENT")
                        .clientId(client.getId())
                        .businessName(client.getName())
                        .contactPerson(client.getOwnerName())
                        .phone(client.getPhone())
                        .whatsappNumber(client.getWhatsappNumber())
                        .email(client.getEmail())
                        .city(client.getAddress())
                        .subscriptionStatus(client.getSubscriptionStatus())
                        .subscriptionExpiryDate(client.getSubscriptionExpiryDate())
                        .isSubscriptionActive(isSubActive)
                        .activeModules(activeMods)
                        .build();
            }
        }

        // 3. Try User fallback
        for (String phoneVar : variants) {
            Optional<User> userOpt = userRepository.findFirstByPhone(phoneVar);
            if (userOpt.isPresent()) {
                User user = userOpt.get();
                Client client = user.getClientId() != null ? clientRepository.findById(user.getClientId()).orElse(null) : null;
                String userFullName = ((user.getFirstName() != null ? user.getFirstName() : "") + " " + (user.getLastName() != null ? user.getLastName() : "")).trim();
                if (userFullName.isEmpty()) userFullName = user.getEmail();
                return MerchantLookupResponse.builder()
                        .matched(true)
                        .matchedType("USER")
                        .orgId(user.getOrgId())
                        .clientId(user.getClientId())
                        .businessName(client != null ? client.getName() : userFullName)
                        .contactPerson(userFullName)
                        .phone(user.getPhone())
                        .email(user.getEmail())
                        .build();
            }
        }

        return MerchantLookupResponse.builder().matched(false).matchedType("NONE").build();
    }

    // ── Lead Retrieval ───────────────────────────────────────────────────────
    public List<FounderLeadDto> getAllLeads(String statusFilter, String searchQuery) {
        List<FounderLead> leads;
        if (statusFilter != null && !statusFilter.isBlank() && !"ALL".equalsIgnoreCase(statusFilter)) {
            leads = leadRepository.findAllByStatusOrderByCreatedAtDesc(statusFilter.toUpperCase());
        } else {
            leads = leadRepository.findAllByOrderByCreatedAtDesc();
        }

        if (searchQuery != null && !searchQuery.isBlank()) {
            String q = searchQuery.toLowerCase().trim();
            leads = leads.stream()
                    .filter(l -> (l.getName() != null && l.getName().toLowerCase().contains(q))
                            || (l.getBusinessName() != null && l.getBusinessName().toLowerCase().contains(q))
                            || (l.getPrimaryPhone() != null && l.getPrimaryPhone().contains(q))
                            || (l.getWhatsappNumber() != null && l.getWhatsappNumber().contains(q))
                            || (l.getCity() != null && l.getCity().toLowerCase().contains(q))
                            || (l.getNotes() != null && l.getNotes().toLowerCase().contains(q)))
                    .collect(Collectors.toList());
        }

        return leads.stream().map(this::mapToDto).collect(Collectors.toList());
    }

    public FounderLeadDto getLeadById(UUID id) {
        FounderLead lead = leadRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Lead not found with id: " + id));
        return mapToDto(lead);
    }

    // ── Lead Creation & Updating ─────────────────────────────────────────────
    @Transactional
    public FounderLeadDto createLead(FounderLeadDto dto) {
        String cleanPhone = normalizePhone(dto.getPrimaryPhone());
        if (cleanPhone.isEmpty()) {
            throw new IllegalArgumentException("Primary phone number is required");
        }

        FounderLead lead = FounderLead.builder()
                .name(dto.getName() != null && !dto.getName().isBlank() ? dto.getName().trim() : "New Prospect")
                .businessName(dto.getBusinessName())
                .primaryPhone(cleanPhone)
                .whatsappNumber(dto.getWhatsappNumber() != null ? normalizePhone(dto.getWhatsappNumber()) : cleanPhone)
                .alternatePhone(dto.getAlternatePhone())
                .email(dto.getEmail())
                .city(dto.getCity())
                .source(dto.getSource() != null ? dto.getSource().toUpperCase() : "PHONE_CALL")
                .status(dto.getStatus() != null ? dto.getStatus().toUpperCase() : "YET_TO_REACH_OUT")
                .temperature(dto.getTemperature() != null ? dto.getTemperature().toUpperCase() : "WARM")
                .dealValueEstimatePaise(dto.getDealValueEstimatePaise() != null ? dto.getDealValueEstimatePaise() : 0L)
                .assignedOrgId(dto.getAssignedOrgId())
                .assignedClientId(dto.getAssignedClientId())
                .nextFollowUpAt(dto.getNextFollowUpAt())
                .followUpNote(dto.getFollowUpNote())
                .lastContactedAt(dto.getLastContactedAt() != null ? dto.getLastContactedAt() : LocalDateTime.now())
                .notes(dto.getNotes())
                .lostReason(dto.getLostReason())
                .build();

        // Check if phone matches any branch or client
        MerchantLookupResponse lookup = resolveMerchantByPhone(cleanPhone);
        if (lookup.isMatched()) {
            if (lookup.getOrgId() != null && lead.getAssignedOrgId() == null) {
                lead.setAssignedOrgId(lookup.getOrgId());
            }
            if (lookup.getClientId() != null && lead.getAssignedClientId() == null) {
                lead.setAssignedClientId(lookup.getClientId());
            }
            if (lead.getBusinessName() == null || lead.getBusinessName().isBlank()) {
                lead.setBusinessName(lookup.getBusinessName());
            }
            if (lookup.isSubscriptionActive()) {
                lead.setStatus("PAID_ACTIVE");
            }
        }

        FounderLead saved = leadRepository.save(lead);

        // Record initial creation activity
        activityRepository.save(FounderLeadActivity.builder()
                .leadId(saved.getId())
                .activityType("STATUS_CHANGE")
                .description("Lead added to " + saved.getStatus() + " via " + saved.getSource())
                .build());

        return mapToDto(saved);
    }

    @Transactional
    public FounderLeadDto updateLead(UUID id, FounderLeadDto dto) {
        FounderLead lead = leadRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Lead not found with id: " + id));

        String oldStatus = lead.getStatus();

        if (dto.getName() != null) lead.setName(dto.getName());
        if (dto.getBusinessName() != null) lead.setBusinessName(dto.getBusinessName());
        if (dto.getPrimaryPhone() != null) lead.setPrimaryPhone(normalizePhone(dto.getPrimaryPhone()));
        if (dto.getWhatsappNumber() != null) lead.setWhatsappNumber(normalizePhone(dto.getWhatsappNumber()));
        if (dto.getAlternatePhone() != null) lead.setAlternatePhone(dto.getAlternatePhone());
        if (dto.getEmail() != null) lead.setEmail(dto.getEmail());
        if (dto.getCity() != null) lead.setCity(dto.getCity());
        if (dto.getSource() != null) lead.setSource(dto.getSource().toUpperCase());
        if (dto.getStatus() != null) lead.setStatus(dto.getStatus().toUpperCase());
        if (dto.getTemperature() != null) lead.setTemperature(dto.getTemperature().toUpperCase());
        if (dto.getDealValueEstimatePaise() != null) lead.setDealValueEstimatePaise(dto.getDealValueEstimatePaise());
        if (dto.getAssignedOrgId() != null) lead.setAssignedOrgId(dto.getAssignedOrgId());
        if (dto.getAssignedClientId() != null) lead.setAssignedClientId(dto.getAssignedClientId());
        if (dto.getNextFollowUpAt() != null) lead.setNextFollowUpAt(dto.getNextFollowUpAt());
        if (dto.getFollowUpNote() != null) lead.setFollowUpNote(dto.getFollowUpNote());
        if (dto.getLastContactedAt() != null) lead.setLastContactedAt(dto.getLastContactedAt());
        if (dto.getNotes() != null) lead.setNotes(dto.getNotes());
        if (dto.getLostReason() != null) lead.setLostReason(dto.getLostReason());

        FounderLead updated = leadRepository.save(lead);

        if (dto.getStatus() != null && !dto.getStatus().equalsIgnoreCase(oldStatus)) {
            activityRepository.save(FounderLeadActivity.builder()
                    .leadId(updated.getId())
                    .activityType("STATUS_CHANGE")
                    .description("Status changed from " + oldStatus + " to " + updated.getStatus())
                    .build());
        }

        return mapToDto(updated);
    }

    @Transactional
    public void deleteLead(UUID id) {
        activityRepository.findAllByLeadIdOrderByCreatedAtDesc(id)
                .forEach(activityRepository::delete);
        leadRepository.deleteById(id);
    }

    // ── Lead Activities ──────────────────────────────────────────────────────
    @Transactional
    public LeadActivityDto addActivity(UUID leadId, String activityType, String description) {
        FounderLead lead = leadRepository.findById(leadId)
                .orElseThrow(() -> new IllegalArgumentException("Lead not found: " + leadId));

        lead.setLastContactedAt(LocalDateTime.now());
        leadRepository.save(lead);

        FounderLeadActivity activity = activityRepository.save(FounderLeadActivity.builder()
                .leadId(leadId)
                .activityType(activityType != null ? activityType.toUpperCase() : "NOTE")
                .description(description)
                .build());

        return LeadActivityDto.builder()
                .id(activity.getId())
                .leadId(activity.getLeadId())
                .activityType(activity.getActivityType())
                .description(activity.getDescription())
                .createdAt(activity.getCreatedAt())
                .build();
    }

    public List<LeadActivityDto> getActivities(UUID leadId) {
        return activityRepository.findAllByLeadIdOrderByCreatedAtDesc(leadId).stream()
                .map(a -> LeadActivityDto.builder()
                        .id(a.getId())
                        .leadId(a.getLeadId())
                        .activityType(a.getActivityType())
                        .description(a.getDescription())
                        .createdAt(a.getCreatedAt())
                        .build())
                .collect(Collectors.toList());
    }

    // ── Follow-Up Action Queue ───────────────────────────────────────────────
    public List<FounderLeadDto> getOverdueFollowUps() {
        return leadRepository.findOverdueFollowUps(LocalDateTime.now()).stream()
                .map(this::mapToDto)
                .collect(Collectors.toList());
    }

    public List<FounderLeadDto> getDueTodayFollowUps() {
        LocalDateTime startOfDay = LocalDateTime.of(LocalDate.now(), LocalTime.MIN);
        LocalDateTime endOfDay = LocalDateTime.of(LocalDate.now(), LocalTime.MAX);
        return leadRepository.findDueFollowUpsBetween(startOfDay, endOfDay).stream()
                .map(this::mapToDto)
                .collect(Collectors.toList());
    }

    // ── Automated WhatsApp Inbound Capture ───────────────────────────────────
    @Transactional
    public FounderLeadDto autoCaptureLeadFromWhatsApp(String senderPhone, String pushName, String messageText) {
        String cleanPhone = normalizePhone(senderPhone);
        if (cleanPhone.isEmpty()) {
            throw new IllegalArgumentException("Invalid sender phone");
        }

        List<FounderLead> existingLeads = leadRepository.findAllByAnyPhone(cleanPhone);
        if (!existingLeads.isEmpty()) {
            FounderLead lead = existingLeads.get(0);
            lead.setLastContactedAt(LocalDateTime.now());
            if ("YET_TO_REACH_OUT".equalsIgnoreCase(lead.getStatus())) {
                lead.setStatus("NEW_INQUIRY");
            }
            leadRepository.save(lead);

            activityRepository.save(FounderLeadActivity.builder()
                    .leadId(lead.getId())
                    .activityType("WHATSAPP")
                    .description("Inbound WhatsApp message: " + (messageText != null ? messageText : ""))
                    .build());

            return mapToDto(lead);
        }

        // New prospect inquiry via WhatsApp
        String leadName = (pushName != null && !pushName.isBlank()) ? pushName.trim() : "WhatsApp Prospect";
        FounderLead newLead = FounderLead.builder()
                .name(leadName)
                .primaryPhone(cleanPhone)
                .whatsappNumber(cleanPhone)
                .source("WHATSAPP")
                .status("NEW_INQUIRY")
                .temperature("WARM")
                .notes("Inbound inquiry: " + (messageText != null ? messageText : ""))
                .lastContactedAt(LocalDateTime.now())
                .build();

        MerchantLookupResponse lookup = resolveMerchantByPhone(cleanPhone);
        if (lookup.isMatched()) {
            newLead.setAssignedOrgId(lookup.getOrgId());
            newLead.setAssignedClientId(lookup.getClientId());
            if (lookup.getBusinessName() != null) {
                newLead.setBusinessName(lookup.getBusinessName());
            }
            if (lookup.isSubscriptionActive()) {
                newLead.setStatus("PAID_ACTIVE");
            }
        }

        FounderLead saved = leadRepository.save(newLead);
        activityRepository.save(FounderLeadActivity.builder()
                .leadId(saved.getId())
                .activityType("WHATSAPP")
                .description("Initial inbound message received: " + (messageText != null ? messageText : ""))
                .build());

        return mapToDto(saved);
    }

    // ── 1-Click Branch & Module Activation ───────────────────────────────────
    @Transactional
    public void activateBranchAndModules(UUID orgId, BranchActivationRequest req) {
        Organization org = organizationRepository.findById(orgId)
                .orElseThrow(() -> new IllegalArgumentException("Branch not found with id: " + orgId));

        LocalDateTime now = LocalDateTime.now();
        LocalDateTime targetExpiry;

        if (req.getSpecificExpiryDate() != null) {
            targetExpiry = req.getSpecificExpiryDate();
        } else if (req.getDaysToAdd() > 0) {
            LocalDateTime base = (org.getSubscriptionExpiryDate() == null || org.getSubscriptionExpiryDate().isBefore(now))
                    ? now
                    : org.getSubscriptionExpiryDate();
            targetExpiry = base.plusDays(req.getDaysToAdd());
        } else {
            targetExpiry = now.plusDays(30);
        }

        String subStatus = req.getSubscriptionStatus() != null && !req.getSubscriptionStatus().isBlank()
                ? req.getSubscriptionStatus().toUpperCase()
                : "ACTIVE";

        org.setSubscriptionExpiryDate(targetExpiry);
        org.setSubscriptionStatus(subStatus);
        org.setActive(true);
        organizationRepository.save(org);

        // Also ensure parent client is active
        if (org.getClientId() != null) {
            clientRepository.findById(org.getClientId()).ifPresent(client -> {
                client.setActive(true);
                if (!"ACTIVE".equalsIgnoreCase(client.getSubscriptionStatus())) {
                    client.setSubscriptionStatus("ACTIVE");
                }
                if (client.getSubscriptionExpiryDate() == null || client.getSubscriptionExpiryDate().isBefore(targetExpiry)) {
                    client.setSubscriptionExpiryDate(targetExpiry);
                }
                clientRepository.save(client);
            });
        }

        // Activate requested add-on modules in client_subscription_modules
        if (req.getActiveModules() != null && !req.getActiveModules().isEmpty()) {
            for (String modNameStr : req.getActiveModules()) {
                try {
                    ModuleName modName = ModuleName.valueOf(modNameStr.trim().toUpperCase());
                    Optional<ClientSubscriptionModule> existingMod = org.getClientId() != null
                            ? moduleRepository.findByClientIdAndOrgIdAndModuleName(org.getClientId(), org.getId(), modName)
                            : Optional.empty();

                    if (existingMod.isPresent()) {
                        ClientSubscriptionModule m = existingMod.get();
                        m.setStatus("ACTIVE");
                        m.setExpiryDate(targetExpiry);
                        moduleRepository.save(m);
                    } else if (org.getClientId() != null) {
                        ClientSubscriptionModule newMod = ClientSubscriptionModule.builder()
                                .clientId(org.getClientId())
                                .orgId(org.getId())
                                .moduleName(modName)
                                .status("ACTIVE")
                                .autoRenew(true)
                                .expiryDate(targetExpiry)
                                .build();
                        moduleRepository.save(newMod);
                    }
                } catch (IllegalArgumentException e) {
                    log.warn("Unknown module name provided in activation: {}", modNameStr);
                }
            }
        }

        // Automatically update any linked lead to PAID_ACTIVE
        leadRepository.findByAssignedOrgId(org.getId()).ifPresent(lead -> {
            lead.setStatus("PAID_ACTIVE");
            leadRepository.save(lead);
            activityRepository.save(FounderLeadActivity.builder()
                    .leadId(lead.getId())
                    .activityType("PAYMENT_RECORDED")
                    .description("Subscription activated until " + targetExpiry.toLocalDate())
                    .build());
        });
    }

    private FounderLeadDto mapToDto(FounderLead l) {
        String branchName = null;
        String clientName = null;
        String subStatus = null;

        if (l.getAssignedOrgId() != null) {
            Optional<Organization> org = organizationRepository.findById(l.getAssignedOrgId());
            if (org.isPresent()) {
                branchName = org.get().getName();
                subStatus = org.get().getSubscriptionStatus();
            }
        }
        if (l.getAssignedClientId() != null) {
            Optional<Client> client = clientRepository.findById(l.getAssignedClientId());
            if (client.isPresent()) {
                clientName = client.get().getName();
                if (subStatus == null) subStatus = client.get().getSubscriptionStatus();
            }
        }

        return FounderLeadDto.builder()
                .id(l.getId())
                .name(l.getName())
                .businessName(l.getBusinessName())
                .primaryPhone(l.getPrimaryPhone())
                .whatsappNumber(l.getWhatsappNumber())
                .alternatePhone(l.getAlternatePhone())
                .email(l.getEmail())
                .city(l.getCity())
                .source(l.getSource())
                .status(l.getStatus())
                .temperature(l.getTemperature())
                .dealValueEstimatePaise(l.getDealValueEstimatePaise())
                .assignedOrgId(l.getAssignedOrgId())
                .assignedClientId(l.getAssignedClientId())
                .nextFollowUpAt(l.getNextFollowUpAt())
                .followUpNote(l.getFollowUpNote())
                .lastContactedAt(l.getLastContactedAt())
                .notes(l.getNotes())
                .lostReason(l.getLostReason())
                .createdAt(l.getCreatedAt())
                .updatedAt(l.getUpdatedAt())
                .matchedBranchName(branchName)
                .matchedClientName(clientName)
                .matchedSubscriptionStatus(subStatus)
                .build();
    }
}
