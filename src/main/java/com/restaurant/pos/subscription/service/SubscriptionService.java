package com.restaurant.pos.subscription.service;

import com.restaurant.pos.client.domain.Client;
import com.restaurant.pos.client.domain.Organization;
import com.restaurant.pos.client.repository.ClientRepository;
import com.restaurant.pos.client.repository.OrganizationRepository;
import com.restaurant.pos.common.context.TimezoneResolver;
import com.restaurant.pos.common.exception.BusinessException;
import com.restaurant.pos.common.exception.ResourceNotFoundException;
import com.restaurant.pos.payment.dto.RazorpayOrderResponse;
import com.restaurant.pos.payment.service.RazorpayService;
import com.restaurant.pos.subscription.domain.ClientSubscriptionModule;
import com.restaurant.pos.subscription.domain.ModuleName;
import com.restaurant.pos.subscription.dto.SubscriptionActivationRequest;
import com.restaurant.pos.subscription.dto.SubscriptionPaymentRequest;
import com.restaurant.pos.subscription.dto.SubscriptionPaymentResponse;
import com.restaurant.pos.subscription.dto.SubscriptionStatusResponse;
import com.restaurant.pos.subscription.repository.ClientSubscriptionModuleRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.List;
import java.util.ArrayList;

@Service
@RequiredArgsConstructor
public class SubscriptionService {

    private static final String ACTIVE = "ACTIVE";
    private static final String TRIAL = "TRIAL";
    private static final String EXPIRED = "EXPIRED";

    private final ClientRepository clientRepository;
    private final OrganizationRepository organizationRepository;
    private final RazorpayService razorpayService;
    private final TimezoneResolver timezoneResolver;
    private final ClientSubscriptionModuleRepository clientSubscriptionModuleRepository;
    private final com.restaurant.pos.subscription.repository.SubscriptionPaymentRepository subscriptionPaymentRepository;

    // Annual Prices in Paise (1 INR = 100 paise)
    private static final long PRICE_BASE_ANNUAL = 99900;          // ₹999/yr
    private static final long PRICE_KOT_ANNUAL = 49900;           // ₹499/yr
    private static final long PRICE_INVENTORY_ANNUAL = 199900;    // ₹1999/yr
    private static final long PRICE_CRM_ANNUAL = 99900;           // ₹999/yr
    private static final long PRICE_CREDIT_LEDGER_ANNUAL = 49900;  // ₹499/yr
    private static final long PRICE_TABLE_QR_ANNUAL = 0;          // Free
    private static final long PRICE_MENU_IMAGES_ANNUAL = 0;        // Free
    private static final long PRICE_ONLINE_DELIVERY_ANNUAL = 0;   // Free
    private static final long PRICE_BARCODE_SCANNER_ANNUAL = 99900; // ₹999/yr

    // One-Time Setup Fee
    private static final long PRICE_SETUP_FEE = 149900;           // ₹1499 setup fee

    @Transactional(readOnly = true)
    public SubscriptionStatusResponse getStatus(UUID clientId, UUID orgId) {
        Client client = findClient(clientId);
        Organization org = null;
        if (orgId != null) {
            org = organizationRepository.findById(orgId).orElse(null);
        }
        return toStatus(client, org);
    }

    @Transactional
    public SubscriptionPaymentResponse createPayment(UUID clientId, SubscriptionPaymentRequest request) {
        Client client = findClient(clientId);
        UUID orgId = request.getOrgId();
        ZoneId zone = timezoneResolver.resolveTimezone(client.getId(), orgId);
        ZonedDateTime nowInZone = ZonedDateTime.now(zone);

        Organization org = null;
        if (orgId != null) {
            org = organizationRepository.findById(orgId).orElse(null);
        }

        // Enforce compulsory base plan and onboarding setup fee if status is UNPAID
        String currentStatus = org != null ? org.getSubscriptionStatus() : client.getSubscriptionStatus();
        boolean isUnpaid = "UNPAID".equalsIgnoreCase(currentStatus);

        long amountPaise = 0;
        boolean hasBase = request.isIncludeBasePlan() || isUnpaid;
        boolean hasSetup = request.isIncludeSetupService() || isUnpaid;

        // 1. Base Plan Calculation
        if (hasBase) {
            amountPaise += PRICE_BASE_ANNUAL;
        }

        // 2. Setup Fee Calculation
        if (hasSetup) {
            amountPaise += PRICE_SETUP_FEE;
        }

        // 3. Module calculations (checking if active branch upgrading mid-cycle)
        boolean isActiveBranch = false;
        LocalDateTime orgExpiry = null;
        if (org != null) {
            isActiveBranch = (ACTIVE.equalsIgnoreCase(org.getSubscriptionStatus()) || TRIAL.equalsIgnoreCase(org.getSubscriptionStatus()))
                    && org.getSubscriptionExpiryDate() != null
                    && org.getSubscriptionExpiryDate().isAfter(LocalDateTime.now());
            orgExpiry = org.getSubscriptionExpiryDate();
        } else {
            isActiveBranch = (ACTIVE.equalsIgnoreCase(client.getSubscriptionStatus()) || TRIAL.equalsIgnoreCase(client.getSubscriptionStatus()))
                    && client.getSubscriptionExpiryDate() != null
                    && client.getSubscriptionExpiryDate().isAfter(LocalDateTime.now());
            orgExpiry = client.getSubscriptionExpiryDate();
        }

        long daysRemaining = 0;
        if (isActiveBranch && orgExpiry != null) {
            ZonedDateTime expiryInZone = orgExpiry.atZone(ZoneId.systemDefault()).withZoneSameInstant(zone);
            daysRemaining = Math.max(0, (long) Math.ceil(Duration.between(nowInZone, expiryInZone).toHours() / 24.0));
        }

        List<String> modulesToBuy = request.getSelectedModules() != null ? request.getSelectedModules() : new ArrayList<>();
        
        for (String mStr : modulesToBuy) {
            ModuleName modName;
            try {
                modName = ModuleName.valueOf(mStr.toUpperCase());
            } catch (Exception e) {
                throw new BusinessException("Invalid module name: " + mStr);
            }
            long moduleAnnualPrice = getModuleAnnualPrice(modName);

            if (isActiveBranch && !hasBase) {
                // Mid-cycle upgrade: Prorated price
                long proratedPrice = Math.round((double) moduleAnnualPrice / 365.0 * (double) daysRemaining);
                proratedPrice = Math.min(proratedPrice, moduleAnnualPrice);
                amountPaise += proratedPrice;
            } else {
                // Checkout base plan + modules: Full annual price
                amountPaise += moduleAnnualPrice;
            }
        }

        if (amountPaise <= 0) {
            throw new BusinessException("Checkout amount must be greater than zero.");
        }

        Map<String, Object> notes = new LinkedHashMap<>();
        notes.put("purpose", "subscription");
        notes.put("client_id", client.getId().toString());
        notes.put("client_name", client.getName() != null ? client.getName() : "");
        notes.put("client_email", client.getEmail() != null ? client.getEmail() : "");
        notes.put("modules", String.join(",", modulesToBuy));
        notes.put("include_base", String.valueOf(hasBase));
        notes.put("include_setup", String.valueOf(hasSetup));
        notes.put("org_id", request.getOrgId() != null ? request.getOrgId().toString() : "");
        notes.put("is_upgrade", String.valueOf(isActiveBranch && !hasBase));

        String receipt = "sub_" + client.getId().toString().substring(0, 8) + "_" + System.currentTimeMillis();
        RazorpayOrderResponse order = razorpayService.createOrder(amountPaise, "INR", receipt, notes);

        return SubscriptionPaymentResponse.builder()
                .orderId(order.getOrderId())
                .amount(order.getAmount())
                .currency(order.getCurrency())
                .keyId(order.getKeyId())
                .planName("Pro Plan Checkout")
                .description("Cafe QR Subscription Checkout")
                .build();
    }

    @Transactional
    public SubscriptionStatusResponse activate(UUID clientId, SubscriptionActivationRequest request) {
        boolean valid = razorpayService.verifyPaymentSignature(
                request.getRazorpayOrderId(),
                request.getRazorpayPaymentId(),
                request.getRazorpaySignature()
        );
        if (!valid) {
            throw new BusinessException("Razorpay payment signature verification failed");
        }

        // Check if payment already processed
        if (subscriptionPaymentRepository.findByPaymentId(request.getRazorpayPaymentId()).isPresent()) {
            Client client = findClient(clientId);
            UUID orgId = com.restaurant.pos.common.tenant.TenantContext.getCurrentOrg();
            Organization org = null;
            if (orgId != null) {
                org = organizationRepository.findById(orgId).orElse(null);
            }
            return toStatus(client, org);
        }

        Client client = findClient(clientId);
        
        com.fasterxml.jackson.databind.JsonNode notesNode = null;
        com.fasterxml.jackson.databind.JsonNode orderNode = null;
        try {
            orderNode = razorpayService.fetchOrder(request.getRazorpayOrderId());
            if (orderNode != null && orderNode.has("notes")) {
                notesNode = orderNode.get("notes");
            }
        } catch (Exception e) {
            throw new BusinessException("Failed to fetch Razorpay order metadata: " + e.getMessage());
        }

        long amount = 0;
        String currency = "INR";
        if (orderNode != null) {
            amount = orderNode.path("amount").asLong(0);
            currency = orderNode.path("currency").asText("INR");
        }

        if (notesNode != null) {
            return activateFromWebhook(clientId, notesNode, request.getRazorpayPaymentId(), request.getRazorpayOrderId(), amount, currency);
        }

        // Fallback baseline activation
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime baseDate = client.getSubscriptionExpiryDate() != null
                && client.getSubscriptionExpiryDate().isAfter(now)
                ? client.getSubscriptionExpiryDate()
                : now;

        client.setSubscriptionStatus(ACTIVE);
        client.setSubscriptionExpiryDate(baseDate.plusYears(1));
        clientRepository.save(client);

        // Log the payment
        com.restaurant.pos.subscription.domain.SubscriptionPayment paymentLog = 
            com.restaurant.pos.subscription.domain.SubscriptionPayment.builder()
                .clientId(clientId)
                .paymentId(request.getRazorpayPaymentId())
                .orderId(request.getRazorpayOrderId())
                .amount(amount)
                .currency(currency)
                .build();
        subscriptionPaymentRepository.save(paymentLog);

        return toStatus(client, null);
    }

    @Transactional
    public SubscriptionStatusResponse activateFromWebhook(UUID clientId, com.fasterxml.jackson.databind.JsonNode notes, String paymentId, String orderId, long amount, String currency) {
        Client client = findClient(clientId);
        
        // Prevent replay attacks/double activations
        if (paymentId != null && subscriptionPaymentRepository.findByPaymentId(paymentId).isPresent()) {
            UUID orgId = null;
            if (notes.has("org_id") && !notes.get("org_id").asText().isBlank()) {
                try {
                    orgId = UUID.fromString(notes.get("org_id").asText());
                } catch (Exception e) {}
            }
            Organization org = null;
            if (orgId != null) {
                org = organizationRepository.findById(orgId).orElse(null);
            }
            return toStatus(client, org);
        }

        UUID orgId = null;
        if (notes.has("org_id") && !notes.get("org_id").asText().isBlank()) {
            try {
                orgId = UUID.fromString(notes.get("org_id").asText());
            } catch (Exception e) {}
        }

        Organization org = null;
        if (orgId != null) {
            org = organizationRepository.findById(orgId).orElse(null);
        }

        ZoneId zone = timezoneResolver.resolveTimezone(client.getId(), orgId);
        ZonedDateTime nowInZone = ZonedDateTime.now(zone);

        boolean hasBase = notes.has("include_base") && Boolean.parseBoolean(notes.get("include_base").asText());
        boolean isUpgrade = notes.has("is_upgrade") && Boolean.parseBoolean(notes.get("is_upgrade").asText());

        LocalDateTime currentExpiry = null;
        if (org != null) {
            currentExpiry = org.getSubscriptionExpiryDate();
        } else {
            currentExpiry = client.getSubscriptionExpiryDate();
        }

        LocalDateTime baseDate;
        if (currentExpiry != null && currentExpiry.isAfter(LocalDateTime.now())) {
            baseDate = currentExpiry;
        } else {
            baseDate = LocalDateTime.now();
        }

        // Align date to end of day in client's timezone: 23:59:59
        ZonedDateTime baseZonedDateTime = baseDate.atZone(ZoneId.systemDefault()).withZoneSameInstant(zone);
        LocalDateTime newExpiry = null;

        if (hasBase) {
            // New base subscription checkout (1 Year)
            ZonedDateTime newExpiryZoned = baseZonedDateTime.plusYears(1)
                    .withHour(23)
                    .withMinute(59)
                    .withSecond(59)
                    .withNano(999000000);
            newExpiry = newExpiryZoned.withZoneSameInstant(ZoneId.systemDefault()).toLocalDateTime();
            
            if (org != null) {
                org.setSubscriptionStatus(ACTIVE);
                org.setSubscriptionExpiryDate(newExpiry);
                organizationRepository.save(org);
            } else {
                client.setSubscriptionStatus(ACTIVE);
                client.setSubscriptionExpiryDate(newExpiry);
                clientRepository.save(client);
            }
        } else {
            newExpiry = currentExpiry;
        }

        // Save module activations
        if (notes.has("modules") && !notes.get("modules").asText().isBlank()) {
            String modulesStr = notes.get("modules").asText();
            String[] modules = modulesStr.split(",");

            // Expiry date: co-terminate to the client/branch base plan expiry
            LocalDateTime moduleExpiry = newExpiry;
            if (moduleExpiry == null) {
                // Fallback: 1 year
                ZonedDateTime targetZoned = nowInZone.plusYears(1)
                        .withHour(23)
                        .withMinute(59)
                        .withSecond(59)
                        .withNano(999000000);
                moduleExpiry = targetZoned.withZoneSameInstant(ZoneId.systemDefault()).toLocalDateTime();
            }

            for (String mStr : modules) {
                ModuleName modName;
                try {
                    modName = ModuleName.valueOf(mStr.toUpperCase().trim());
                } catch (Exception e) {
                    continue;
                }

                // Scoping rules: CRM & CREDIT_LEDGER are client-scoped, others (KOT, INVENTORY) are branch-scoped
                UUID moduleOrgId = null;
                if (modName == ModuleName.KOT || modName == ModuleName.INVENTORY) {
                    moduleOrgId = orgId;
                }

                ClientSubscriptionModule existing = null;
                if (moduleOrgId != null) {
                    existing = clientSubscriptionModuleRepository.findByClientIdAndOrgIdAndModuleName(client.getId(), moduleOrgId, modName).orElse(null);
                } else {
                    existing = clientSubscriptionModuleRepository.findByClientIdAndModuleName(client.getId(), modName)
                            .filter(m -> m.getOrgId() == null)
                            .orElse(null);
                }

                if (existing == null) {
                    existing = ClientSubscriptionModule.builder()
                            .clientId(client.getId())
                            .orgId(moduleOrgId)
                            .moduleName(modName)
                            .build();
                }

                existing.setStatus("ACTIVE");
                existing.setExpiryDate(moduleExpiry);
                existing.setAutoRenew(true);
                clientSubscriptionModuleRepository.save(existing);
            }
        }

        // Log payment transaction to database
        if (paymentId != null && orderId != null) {
            com.restaurant.pos.subscription.domain.SubscriptionPayment paymentLog = 
                com.restaurant.pos.subscription.domain.SubscriptionPayment.builder()
                    .clientId(clientId)
                    .orgId(orgId)
                    .paymentId(paymentId)
                    .orderId(orderId)
                    .amount(amount)
                    .currency(currency != null ? currency : "INR")
                    .build();
            subscriptionPaymentRepository.save(paymentLog);
        }

        return toStatus(client, org);
    }

    private Client findClient(UUID clientId) {
        if (clientId == null) {
            throw new BusinessException("Client context is missing");
        }
        return clientRepository.findById(clientId)
                .orElseThrow(() -> new ResourceNotFoundException("Client not found with id: " + clientId));
    }

    private SubscriptionStatusResponse toStatus(Client client, Organization org) {
        ZoneId zone = timezoneResolver.resolveTimezone(client.getId(), org != null ? org.getId() : null);
        ZonedDateTime nowInZone = ZonedDateTime.now(zone);

        String status;
        LocalDateTime expiry;
        if (org != null) {
            status = normalizeStatus(org.getSubscriptionStatus());
            expiry = org.getSubscriptionExpiryDate();
        } else {
            status = normalizeStatus(client.getSubscriptionStatus());
            expiry = client.getSubscriptionExpiryDate();
        }
        
        ZonedDateTime expiryInZone = expiry != null ? expiry.atZone(ZoneId.systemDefault()).withZoneSameInstant(zone) : null;

        boolean active = (ACTIVE.equals(status) || TRIAL.equals(status)) && expiryInZone != null && !expiryInZone.isBefore(nowInZone);
        int daysLeft = active ? Math.max(0, (int) Math.ceil(Duration.between(nowInZone, expiryInZone).toHours() / 24.0)) : 0;

        if (!active && (ACTIVE.equals(status) || TRIAL.equals(status))) {
            status = EXPIRED;
        }

        String message = active
                ? (TRIAL.equals(status) ? "Free trial active" : "Paid subscription active")
                : "Subscription expired";

        List<ClientSubscriptionModule> modules = clientSubscriptionModuleRepository.findByClientId(client.getId());
        List<ModuleName> activeModulesList = new ArrayList<>();
        List<String> activeModulesDetailed = new ArrayList<>();
        for (ClientSubscriptionModule m : modules) {
            if ("ACTIVE".equalsIgnoreCase(m.getStatus())) {
                if (m.getExpiryDate() == null || m.getExpiryDate().isAfter(LocalDateTime.now())) {
                    // Check if module matches scope
                    if (m.getOrgId() == null || (org != null && m.getOrgId().equals(org.getId()))) {
                        activeModulesList.add(m.getModuleName());
                        activeModulesDetailed.add(m.getModuleName().name());
                    }
                }
            }
        }

        return SubscriptionStatusResponse.builder()
                .active(active)
                .status(status)
                .daysLeft(daysLeft)
                .expiryDate(expiry)
                .message(message)
                .activeModules(activeModulesList)
                .activeModulesDetailed(activeModulesDetailed)
                .build();
    }

    private long getModuleAnnualPrice(ModuleName moduleName) {
        switch (moduleName) {
            case KOT: return PRICE_KOT_ANNUAL;
            case INVENTORY: return PRICE_INVENTORY_ANNUAL;
            case CRM: return PRICE_CRM_ANNUAL;
            case CREDIT_LEDGER: return PRICE_CREDIT_LEDGER_ANNUAL;
            case TABLE_QR: return PRICE_TABLE_QR_ANNUAL;
            case MENU_IMAGES: return PRICE_MENU_IMAGES_ANNUAL;
            case ONLINE_DELIVERY: return PRICE_ONLINE_DELIVERY_ANNUAL;
            case BARCODE_SCANNER: return PRICE_BARCODE_SCANNER_ANNUAL;
            default: return 0;
        }
    }

    private String normalizeStatus(String status) {
        if (status == null || status.isBlank()) {
            return EXPIRED;
        }
        return status.trim().toUpperCase();
    }
}
