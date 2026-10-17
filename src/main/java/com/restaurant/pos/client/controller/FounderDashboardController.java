package com.restaurant.pos.client.controller;

import com.restaurant.pos.client.domain.Client;
import com.restaurant.pos.client.dto.ClientSummaryDto;
import com.restaurant.pos.client.dto.FounderDashboardResponse;
import com.restaurant.pos.client.repository.ClientRepository;
import com.restaurant.pos.common.dto.ApiResponse;
import com.restaurant.pos.subscription.domain.ClientSubscriptionModule;
import com.restaurant.pos.subscription.domain.SubscriptionPayment;
import com.restaurant.pos.subscription.repository.ClientSubscriptionModuleRepository;
import com.restaurant.pos.subscription.repository.SubscriptionPaymentRepository;
import com.restaurant.pos.auth.domain.User;
import com.restaurant.pos.auth.dto.AuthResponse;
import com.restaurant.pos.auth.repository.UserRepository;
import com.restaurant.pos.auth.service.AuthService;
import com.restaurant.pos.client.dto.ClientCountProjection;
import com.restaurant.pos.client.dto.ClientOrderStatsProjection;
import com.restaurant.pos.order.repository.OrderRepository;
import com.restaurant.pos.product.repository.ProductRepository;
import com.restaurant.pos.table.repository.RestaurantTableRepository;
import com.restaurant.pos.client.domain.Organization;
import com.restaurant.pos.client.repository.OrganizationRepository;
import com.restaurant.pos.founder.lead.dto.*;
import com.restaurant.pos.founder.lead.service.FounderLeadService;
import jakarta.servlet.http.HttpServletRequest;
import java.math.BigDecimal;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Founder-only dashboard endpoint.
 *
 * This endpoint is intentionally NOT protected by Spring Security JWT auth —
 * it is in the permitAll list. Instead it validates its own X-Founder-Key header
 * against the configured founder.secret-key property. No client token, no role check.
 *
 * No client of ours can ever access this — only the founders who know the secret key.
 */
@RestController
@RequestMapping("/api/v1/founder")
@RequiredArgsConstructor
@Slf4j
public class FounderDashboardController {

    private final ClientRepository clientRepository;
    private final OrganizationRepository organizationRepository;
    private final ClientSubscriptionModuleRepository moduleRepository;
    private final SubscriptionPaymentRepository paymentRepository;
    private final OrderRepository orderRepository;
    private final ProductRepository productRepository;
    private final RestaurantTableRepository tableRepository;
    private final UserRepository userRepository;
    private final AuthService authService;
    private final FounderLeadService founderLeadService;
    private final com.restaurant.pos.whatsapp.service.WhatsAppService whatsAppService;

    @Value("${founder.secret-key}")
    private String founderSecretKey;

    /** Constant-time comparison so the key cannot be recovered through response timing. */
    private boolean isValidKey(String providedKey) {
        return providedKey != null && founderSecretKey != null
                && java.security.MessageDigest.isEqual(
                        providedKey.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                        founderSecretKey.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    @GetMapping("/dashboard")
    public ResponseEntity<?> getFounderDashboard(
            @RequestHeader(value = "X-Founder-Key", required = false) String providedKey) {

        // ── Validate secret key ───────────────────────────────────────────────
        if (!isValidKey(providedKey)) {
            log.warn("[FounderDashboard] Unauthorized access attempt (key {})", providedKey == null ? "missing" : "invalid");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(ApiResponse.error("Unauthorized"));
        }

        log.info("[FounderDashboard] Authorized — loading all client data");

        // ── Fetch all data ────────────────────────────────────────────────────
        List<Client> allClients = clientRepository.findAll();
        List<Organization> allOrganizations = organizationRepository.findAll();
        List<ClientSubscriptionModule> allModules = moduleRepository.findAll();
        List<SubscriptionPayment> allPayments = paymentRepository.findAllByOrderByCreatedAtDesc();

        // ── Deduplicate clients by UUID (guards against any DB/JPA duplicates) ─
        List<Client> uniqueClients = allClients.stream()
                .collect(Collectors.toMap(Client::getId, c -> c, (a, b) -> a))
                .values().stream().collect(Collectors.toList());

        // ── Group by clientId for O(1) lookup ─────────────────────────────────
        Map<UUID, List<Organization>> organizationsByClient = allOrganizations.stream()
                .filter(o -> o.getClientId() != null)
                .collect(Collectors.groupingBy(Organization::getClientId));

        Map<UUID, List<ClientSubscriptionModule>> modulesByClient = allModules.stream()
                .collect(Collectors.groupingBy(ClientSubscriptionModule::getClientId));

        Map<UUID, List<SubscriptionPayment>> paymentsByClient = allPayments.stream()
                .collect(Collectors.groupingBy(SubscriptionPayment::getClientId));

        LocalDateTime now = LocalDateTime.now();

        // ── Batched Telemetry Aggregations (O(1) in-memory lookup) ───────────
        List<ClientOrderStatsProjection> orderStats = orderRepository.aggregateClientOrderStats();
        Map<UUID, ClientOrderStatsProjection> orderStatsByClient = orderStats.stream()
                .collect(Collectors.toMap(ClientOrderStatsProjection::getClientId, p -> p, (a, b) -> a));

        List<ClientCountProjection> productCounts = productRepository.countActiveProductsPerClient();
        Map<UUID, Long> productCountsByClient = productCounts.stream()
                .collect(Collectors.toMap(ClientCountProjection::getClientId, ClientCountProjection::getCount, (a, b) -> a));

        List<ClientCountProjection> tableCounts = tableRepository.countActiveTablesPerClient();
        Map<UUID, Long> tableCountsByClient = tableCounts.stream()
                .collect(Collectors.toMap(ClientCountProjection::getClientId, ClientCountProjection::getCount, (a, b) -> a));

        long activeCafesToday = orderRepository.countActiveClientsSince(now.minusHours(24));
        long activeCafes7Days = orderRepository.countActiveClientsSince(now.minusDays(7));
        long totalOrdersCount = orderRepository.countAllSaleOrders();
        BigDecimal totalGmv = orderRepository.sumPlatformGmv();
        long platformGmvPaise = totalGmv != null ? totalGmv.multiply(BigDecimal.valueOf(100)).longValue() : 0L;

        // ── Build per-client summaries with Branch-First Fallback Resolution ──
        List<ClientSummaryDto> clientSummaries = uniqueClients.stream()
                .map(client -> {
                    List<Organization> orgs = organizationsByClient.getOrDefault(client.getId(), List.of());
                    Organization primaryOrg = orgs.isEmpty() ? null : orgs.get(0);

                    List<ClientSubscriptionModule> mods = modulesByClient.getOrDefault(client.getId(), List.of());
                    List<SubscriptionPayment> payments = paymentsByClient.getOrDefault(client.getId(), List.of());

                    // Branch summaries
                    List<ClientSummaryDto.BranchSummaryDto> branchSummaries = orgs.stream()
                            .map(o -> {
                                int bDaysLeft = 0;
                                if (o.getSubscriptionExpiryDate() != null && o.getSubscriptionExpiryDate().isAfter(now)) {
                                    bDaysLeft = (int) ChronoUnit.DAYS.between(now, o.getSubscriptionExpiryDate());
                                }
                                List<ClientSummaryDto.ModuleInfo> bMods = mods.stream()
                                        .filter(m -> o.getId().equals(m.getOrgId()))
                                        .map(m -> ClientSummaryDto.ModuleInfo.builder()
                                                .moduleName(m.getModuleName().name())
                                                .status(m.getStatus())
                                                .expiryDate(m.getExpiryDate())
                                                .autoRenew(m.isAutoRenew())
                                                .build())
                                        .collect(Collectors.toList());

                                return ClientSummaryDto.BranchSummaryDto.builder()
                                        .id(o.getId())
                                        .clientId(o.getClientId())
                                        .name(o.getName())
                                        .branchCode(o.getBranchCode())
                                        .slug(o.getSlug())
                                        .phone(o.getPhone())
                                        .whatsappNumber(o.getWhatsappNumber())
                                        .address(o.getAddress())
                                        .isactive(o.getIsactive())
                                        .subscriptionStatus(o.getSubscriptionStatus())
                                        .subscriptionExpiryDate(o.getSubscriptionExpiryDate())
                                        .daysLeft(bDaysLeft)
                                        .modules(bMods)
                                        .build();
                            })
                            .collect(Collectors.toList());

                    // Accurate Effective Subscription Status Resolution
                    LocalDateTime effectiveExpiry = client.getSubscriptionExpiryDate();
                    for (Organization o : orgs) {
                        if (o.getSubscriptionExpiryDate() != null && (effectiveExpiry == null || o.getSubscriptionExpiryDate().isAfter(effectiveExpiry))) {
                            effectiveExpiry = o.getSubscriptionExpiryDate();
                        }
                    }

                    boolean hasActiveModule = mods.stream().anyMatch(m -> "ACTIVE".equalsIgnoreCase(m.getStatus()) && (m.getExpiryDate() == null || m.getExpiryDate().isAfter(now)));
                    boolean hasActiveBranch = orgs.stream().anyMatch(o -> o.getSubscriptionExpiryDate() != null && o.getSubscriptionExpiryDate().isAfter(now));
                    boolean clientExpired = client.getSubscriptionExpiryDate() != null && client.getSubscriptionExpiryDate().isBefore(now);

                    String effectiveStatus = "UNPAID";
                    if (hasActiveBranch || (effectiveExpiry != null && effectiveExpiry.isAfter(now)) || hasActiveModule || "ACTIVE".equalsIgnoreCase(client.getSubscriptionStatus())) {
                        effectiveStatus = "ACTIVE";
                    } else if ("TRIAL".equalsIgnoreCase(client.getSubscriptionStatus())) {
                        effectiveStatus = "TRIAL";
                    } else if (clientExpired) {
                        effectiveStatus = "EXPIRED";
                    }

                    int effectiveDaysLeft = 0;
                    if (effectiveExpiry != null && effectiveExpiry.isAfter(now)) {
                        effectiveDaysLeft = (int) ChronoUnit.DAYS.between(now, effectiveExpiry);
                    }

                    int daysLeft = 0;
                    if (client.getSubscriptionExpiryDate() != null && client.getSubscriptionExpiryDate().isAfter(now)) {
                        daysLeft = (int) ChronoUnit.DAYS.between(now, client.getSubscriptionExpiryDate());
                    }

                    // Deduplicate modules by name — prefer ACTIVE status, else keep first found
                    List<ClientSummaryDto.ModuleInfo> moduleInfos = mods.stream()
                            .collect(Collectors.toMap(
                                    m -> m.getModuleName().name(),
                                    m -> m,
                                    (existing, incoming) -> "ACTIVE".equalsIgnoreCase(existing.getStatus()) ? existing : incoming
                            ))
                            .values().stream()
                            .sorted(Comparator.comparing(m -> m.getModuleName().name()))
                            .map(m -> ClientSummaryDto.ModuleInfo.builder()
                                    .moduleName(m.getModuleName().name())
                                    .status(m.getStatus())
                                    .expiryDate(m.getExpiryDate())
                                    .autoRenew(m.isAutoRenew())
                                    .build())
                            .collect(Collectors.toList());

                    List<ClientSummaryDto.PaymentInfo> paymentInfos = payments.stream()
                            .map(p -> ClientSummaryDto.PaymentInfo.builder()
                                    .id(p.getId())
                                    .paymentId(p.getPaymentId())
                                    .orderId(p.getOrderId())
                                    .amount(p.getAmount())
                                    .currency(p.getCurrency())
                                    .createdAt(p.getCreatedAt())
                                    .build())
                            .collect(Collectors.toList());

                    long totalPaid = payments.stream().mapToLong(SubscriptionPayment::getAmount).sum();

                    // Client Activity & Vitality
                    ClientOrderStatsProjection oStat = orderStatsByClient.get(client.getId());
                    long clientOrderCount = oStat != null && oStat.getOrderCount() != null ? oStat.getOrderCount() : 0L;
                    long clientGmvPaise = oStat != null && oStat.getTotalGmv() != null 
                            ? oStat.getTotalGmv().multiply(BigDecimal.valueOf(100)).longValue() 
                            : 0L;
                    LocalDateTime lastOrderAt = oStat != null ? oStat.getLastOrderAt() : null;

                    int menuCount = productCountsByClient.getOrDefault(client.getId(), 0L).intValue();
                    int tableCount = tableCountsByClient.getOrDefault(client.getId(), 0L).intValue();

                    // Branch-first fallbacks for display
                    String resolvedName = (primaryOrg != null && primaryOrg.getName() != null && !primaryOrg.getName().isBlank() && (client.getName() == null || client.getName().isBlank() || "—".equals(client.getName())))
                            ? primaryOrg.getName()
                            : client.getName();
                    String resolvedPhone = (primaryOrg != null && primaryOrg.getPhone() != null && !primaryOrg.getPhone().isBlank() && (client.getPhone() == null || client.getPhone().isBlank()))
                            ? primaryOrg.getPhone()
                            : client.getPhone();
                    String resolvedWhatsapp = (primaryOrg != null && primaryOrg.getWhatsappNumber() != null && !primaryOrg.getWhatsappNumber().isBlank() && (client.getWhatsappNumber() == null || client.getWhatsappNumber().isBlank()))
                            ? primaryOrg.getWhatsappNumber()
                            : client.getWhatsappNumber();

                    // Onboarding Step & Score
                    boolean hasName = resolvedName != null && !resolvedName.isBlank() && !"—".equals(resolvedName);
                    boolean hasPhone = resolvedPhone != null && !resolvedPhone.isBlank();
                    boolean profileSet = hasName && hasPhone;

                    int score = 20; // Signed up
                    String onboardingStep = "PROFILE_INCOMPLETE";
                    if (profileSet) {
                        score = 40;
                        onboardingStep = "MENU_PENDING";
                        if (menuCount >= 3) {
                            score = 60;
                            onboardingStep = "TABLES_PENDING";
                            if (tableCount >= 1) {
                                score = 80;
                                onboardingStep = "FIRST_ORDER_PENDING";
                                if (clientOrderCount >= 1) {
                                    score = 100;
                                    onboardingStep = "ACTIVATED";
                                }
                            }
                        }
                    } else if (menuCount >= 3 || tableCount >= 1 || clientOrderCount >= 1) {
                        score = Math.max(40, (menuCount >= 3 ? 60 : 40) + (clientOrderCount >= 1 ? 40 : 0));
                        onboardingStep = clientOrderCount >= 1 ? "ACTIVATED" : "PROFILE_INCOMPLETE";
                    }

                    // Health Status Classification
                    String healthStatus;
                    if ("EXPIRED".equals(effectiveStatus)) {
                        healthStatus = "EXPIRED";
                    } else if (lastOrderAt != null && lastOrderAt.isAfter(now.minusDays(7))) {
                        healthStatus = "HEALTHY";
                    } else if (clientOrderCount > 0 && (lastOrderAt == null || lastOrderAt.isBefore(now.minusDays(7)))) {
                        healthStatus = "AT_RISK";
                    } else if (client.getCreatedAt() != null && client.getCreatedAt().isBefore(now.minusDays(7)) && clientOrderCount == 0) {
                        healthStatus = "DORMANT";
                    } else {
                        healthStatus = "HEALTHY";
                    }

                    return ClientSummaryDto.builder()
                            .id(client.getId())
                            .name(resolvedName)
                            .legalName(client.getLegalName())
                            .ownerName(client.getOwnerName())
                            .email(client.getEmail())
                            .phone(resolvedPhone)
                            .address(client.getAddress())
                            .country(client.getCountry())
                            .currency(client.getCurrency())
                            .timezone(client.getTimezone())
                            .posType(client.getPosType())
                            .website(client.getWebsite())
                            .panNumber(client.getPanNumber())
                            .gstNumber(client.getGstNumber())
                            .fssaiNumber(client.getFssaiNumber())
                            .instagramUrl(client.getInstagramUrl())
                            .facebookUrl(client.getFacebookUrl())
                            .whatsappNumber(resolvedWhatsapp)
                            .bankName(client.getBankName())
                            .accountNumber(client.getAccountNumber())
                            .ifscCode(client.getIfscCode())
                            .isactive(client.isActive() ? "Y" : "N")
                            .createdAt(client.getCreatedAt())
                            .updatedAt(client.getUpdatedAt())
                            .subscriptionStatus(client.getSubscriptionStatus())
                            .subscriptionExpiryDate(client.getSubscriptionExpiryDate())
                            .daysLeft(daysLeft)
                            .subscriptionModules(moduleInfos)
                            .paymentHistory(paymentInfos)
                            .totalPaidPaise(totalPaid)
                            .paymentCount(payments.size())
                            .gmvPaise(clientGmvPaise)
                            .orderCount(clientOrderCount)
                            .lastOrderAt(lastOrderAt)
                            .menuItemsCount(menuCount)
                            .tablesCount(tableCount)
                            .onboardingStep(onboardingStep)
                            .onboardingScore(score)
                            .healthStatus(healthStatus)
                            .primaryOrgId(primaryOrg != null ? primaryOrg.getId() : null)
                            .primaryBranchName(primaryOrg != null ? primaryOrg.getName() : null)
                            .primaryBranchCode(primaryOrg != null ? primaryOrg.getBranchCode() : null)
                            .branchCount(orgs.size())
                            .branches(branchSummaries)
                            .effectiveSubscriptionStatus(effectiveStatus)
                            .effectiveExpiryDate(effectiveExpiry)
                            .effectiveDaysLeft(effectiveDaysLeft)
                            .build();
                })
                .sorted(Comparator.comparing(c -> c.getName() == null ? "" : c.getName()))
                .collect(Collectors.toList());

        // ── Aggregate stats ────────────────────────────────────────────────────
        long totalRevenue = allPayments.stream().mapToLong(SubscriptionPayment::getAmount).sum();
        int active = 0, trial = 0, expired = 0, unpaid = 0;
        for (ClientSummaryDto c : clientSummaries) {
            String status = c.getEffectiveSubscriptionStatus() != null ? c.getEffectiveSubscriptionStatus().toUpperCase() : "";
            switch (status) {
                case "ACTIVE" -> active++;
                case "TRIAL" -> trial++;
                case "EXPIRED" -> expired++;
                case "UNPAID" -> unpaid++;
                default -> {}
            }
        }

        // Paying clients
        List<ClientSummaryDto> payingClients = clientSummaries.stream()
                .filter(c -> "ACTIVE".equalsIgnoreCase(c.getEffectiveSubscriptionStatus()) && c.getTotalPaidPaise() > 0)
                .collect(Collectors.toList());
        long totalPayingRev = payingClients.stream().mapToLong(ClientSummaryDto::getTotalPaidPaise).sum();
        long arpuPaise = payingClients.isEmpty() ? 0L : totalPayingRev / payingClients.size();
        
        long mrrPaise = payingClients.stream().mapToLong(c -> {
            if (c.getPaymentHistory() != null && !c.getPaymentHistory().isEmpty()) {
                return c.getPaymentHistory().get(0).getAmount();
            }
            return 0L;
        }).sum();

        int activationRatePercent = clientSummaries.isEmpty() ? 0 
                : (int) Math.round((clientSummaries.stream().filter(c -> c.getOrderCount() > 0).count() * 100.0) / clientSummaries.size());

        FounderDashboardResponse response = FounderDashboardResponse.builder()
                .stats(FounderDashboardResponse.Stats.builder()
                        .totalClients(clientSummaries.size())
                        .activeClients(active)
                        .trialClients(trial)
                        .expiredClients(expired)
                        .unpaidClients(unpaid)
                        .totalRevenuePaise(totalRevenue)
                        .platformGmvPaise(platformGmvPaise)
                        .totalOrdersCount(totalOrdersCount)
                        .activeCafesToday(activeCafesToday)
                        .activeCafes7Days(activeCafes7Days)
                        .mrrPaise(mrrPaise)
                        .arpuPaise(arpuPaise)
                        .activationRatePercent(activationRatePercent)
                        .build())
                .clients(clientSummaries)
                .build();

        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @PostMapping("/client/{clientId}/impersonate")
    public ResponseEntity<?> impersonateClient(
            @PathVariable UUID clientId,
            @RequestHeader(value = "X-Founder-Key", required = false) String providedKey,
            HttpServletRequest request) {

        if (!isValidKey(providedKey)) {
            log.warn("[FounderDashboard] Unauthorized impersonation attempt for client {}", clientId);
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(ApiResponse.error("Unauthorized"));
        }

        Client client = clientRepository.findById(clientId).orElse(null);
        if (client == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiResponse.error("Client not found"));
        }

        List<User> users = userRepository.findAllByClientId(clientId);
        if (users.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiResponse.error("No user accounts found for this client"));
        }

        User targetUser = users.stream()
                .filter(u -> u.getRoleEntity() != null && "ADMIN".equalsIgnoreCase(u.getRoleEntity().getName()))
                .findFirst()
                .orElse(users.get(0));

        log.info("[FounderDashboard] Founder impersonating client {} ({}) as user {}", clientId, client.getName(), targetUser.getEmail());

        String ip = request.getRemoteAddr();
        String userAgent = request.getHeader("User-Agent");
        AuthResponse authResponse = authService.authenticateDirectly(targetUser, ip, userAgent);

        return ResponseEntity.ok(ApiResponse.success(authResponse));
    }

    @lombok.Data
    public static class ExtendSubscriptionRequest {
        private int daysToAdd;
    }

    @lombok.Data
    public static class RecordPaymentRequest {
        private long amountPaise;
        private String paymentId;
        private String orderId;
    }

    @PostMapping("/client/{clientId}/toggle-active")
    public ResponseEntity<?> toggleClientActive(
            @PathVariable UUID clientId,
            @RequestHeader(value = "X-Founder-Key", required = false) String providedKey) {
        if (!isValidKey(providedKey)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(ApiResponse.error("Unauthorized"));
        }
        Client client = clientRepository.findById(clientId).orElse(null);
        if (client == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiResponse.error("Client not found"));
        }
        client.setActive(!client.isActive());
        clientRepository.save(client);
        return ResponseEntity.ok(ApiResponse.success("Status toggled successfully"));
    }

    @PostMapping("/client/{clientId}/extend-subscription")
    public ResponseEntity<?> extendSubscription(
            @PathVariable UUID clientId,
            @RequestBody ExtendSubscriptionRequest req,
            @RequestHeader(value = "X-Founder-Key", required = false) String providedKey) {
        if (!isValidKey(providedKey)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(ApiResponse.error("Unauthorized"));
        }
        Client client = clientRepository.findById(clientId).orElse(null);
        if (client == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiResponse.error("Client not found"));
        }
        
        LocalDateTime currentExpiry = client.getSubscriptionExpiryDate();
        LocalDateTime baseDate = (currentExpiry == null || currentExpiry.isBefore(LocalDateTime.now())) 
                ? LocalDateTime.now() 
                : currentExpiry;
        
        client.setSubscriptionExpiryDate(baseDate.plusDays(req.getDaysToAdd()));
        
        // Ensure status is active/trial if it was expired or unpaid
        if (!"ACTIVE".equalsIgnoreCase(client.getSubscriptionStatus()) && !"TRIAL".equalsIgnoreCase(client.getSubscriptionStatus())) {
            client.setSubscriptionStatus("ACTIVE");
        }
        clientRepository.save(client);
        return ResponseEntity.ok(ApiResponse.success("Subscription extended successfully"));
    }

    @PostMapping("/client/{clientId}/record-payment")
    public ResponseEntity<?> recordManualPayment(
            @PathVariable UUID clientId,
            @RequestBody RecordPaymentRequest req,
            @RequestHeader(value = "X-Founder-Key", required = false) String providedKey) {
        if (!isValidKey(providedKey)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(ApiResponse.error("Unauthorized"));
        }
        Client client = clientRepository.findById(clientId).orElse(null);
        if (client == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiResponse.error("Client not found"));
        }
        
        long count = paymentRepository.count() + 1;
        String invoiceNo = String.format("CQ-INV-%05d", count);
        String orderNo = String.format("CQ-ORD-%05d", count);
        
        SubscriptionPayment payment = SubscriptionPayment.builder()
                .clientId(clientId)
                .amount(req.getAmountPaise())
                .paymentId(invoiceNo)
                .orderId(orderNo)
                .currency("INR")
                .createdAt(LocalDateTime.now())
                .build();
        paymentRepository.save(payment);
        return ResponseEntity.ok(ApiResponse.success("Payment recorded successfully"));
    }

    // ── Sales CRM: Lead Management Endpoints ─────────────────────────────────

    @GetMapping("/leads")
    public ResponseEntity<?> getLeads(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String search,
            @RequestHeader(value = "X-Founder-Key", required = false) String providedKey) {
        if (!isValidKey(providedKey)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(ApiResponse.error("Unauthorized"));
        }
        return ResponseEntity.ok(ApiResponse.success(founderLeadService.getAllLeads(status, search)));
    }

    @GetMapping("/leads/{id}")
    public ResponseEntity<?> getLeadById(
            @PathVariable UUID id,
            @RequestHeader(value = "X-Founder-Key", required = false) String providedKey) {
        if (!isValidKey(providedKey)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(ApiResponse.error("Unauthorized"));
        }
        return ResponseEntity.ok(ApiResponse.success(founderLeadService.getLeadById(id)));
    }

    @PostMapping("/leads")
    public ResponseEntity<?> createLead(
            @RequestBody FounderLeadDto dto,
            @RequestHeader(value = "X-Founder-Key", required = false) String providedKey) {
        if (!isValidKey(providedKey)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(ApiResponse.error("Unauthorized"));
        }
        return ResponseEntity.ok(ApiResponse.success(founderLeadService.createLead(dto)));
    }

    @PutMapping("/leads/{id}")
    public ResponseEntity<?> updateLead(
            @PathVariable UUID id,
            @RequestBody FounderLeadDto dto,
            @RequestHeader(value = "X-Founder-Key", required = false) String providedKey) {
        if (!isValidKey(providedKey)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(ApiResponse.error("Unauthorized"));
        }
        return ResponseEntity.ok(ApiResponse.success(founderLeadService.updateLead(id, dto)));
    }

    @DeleteMapping("/leads/{id}")
    public ResponseEntity<?> deleteLead(
            @PathVariable UUID id,
            @RequestHeader(value = "X-Founder-Key", required = false) String providedKey) {
        if (!isValidKey(providedKey)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(ApiResponse.error("Unauthorized"));
        }
        founderLeadService.deleteLead(id);
        return ResponseEntity.ok(ApiResponse.success("Lead deleted successfully"));
    }

    @GetMapping("/leads/{id}/activities")
    public ResponseEntity<?> getLeadActivities(
            @PathVariable UUID id,
            @RequestHeader(value = "X-Founder-Key", required = false) String providedKey) {
        if (!isValidKey(providedKey)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(ApiResponse.error("Unauthorized"));
        }
        return ResponseEntity.ok(ApiResponse.success(founderLeadService.getActivities(id)));
    }

    @PostMapping("/leads/{id}/activity")
    public ResponseEntity<?> addLeadActivity(
            @PathVariable UUID id,
            @RequestBody LeadActivityDto req,
            @RequestHeader(value = "X-Founder-Key", required = false) String providedKey) {
        if (!isValidKey(providedKey)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(ApiResponse.error("Unauthorized"));
        }
        return ResponseEntity.ok(ApiResponse.success(founderLeadService.addActivity(id, req.getActivityType(), req.getDescription())));
    }

    @GetMapping("/leads/queue/overdue")
    public ResponseEntity<?> getOverdueFollowUps(
            @RequestHeader(value = "X-Founder-Key", required = false) String providedKey) {
        if (!isValidKey(providedKey)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(ApiResponse.error("Unauthorized"));
        }
        return ResponseEntity.ok(ApiResponse.success(founderLeadService.getOverdueFollowUps()));
    }

    @GetMapping("/leads/queue/due-today")
    public ResponseEntity<?> getDueTodayFollowUps(
            @RequestHeader(value = "X-Founder-Key", required = false) String providedKey) {
        if (!isValidKey(providedKey)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(ApiResponse.error("Unauthorized"));
        }
        return ResponseEntity.ok(ApiResponse.success(founderLeadService.getDueTodayFollowUps()));
    }

    @GetMapping("/lookup-merchant")
    public ResponseEntity<?> lookupMerchant(
            @RequestParam String phone,
            @RequestHeader(value = "X-Founder-Key", required = false) String providedKey) {
        if (!isValidKey(providedKey)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(ApiResponse.error("Unauthorized"));
        }
        return ResponseEntity.ok(ApiResponse.success(founderLeadService.resolveMerchantByPhone(phone)));
    }

    // ── 1-Click Branch & Module Activation ───────────────────────────────────

    @PostMapping("/organization/{orgId}/activate-subscription")
    public ResponseEntity<?> activateOrganizationSubscription(
            @PathVariable UUID orgId,
            @RequestBody BranchActivationRequest req,
            @RequestHeader(value = "X-Founder-Key", required = false) String providedKey) {
        if (!isValidKey(providedKey)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(ApiResponse.error("Unauthorized"));
        }
        founderLeadService.activateBranchAndModules(orgId, req);
        return ResponseEntity.ok(ApiResponse.success("Branch subscription and add-on modules updated successfully"));
    }

    // ── Official WhatsApp Gateway Management ─────────────────────────────────

    @GetMapping("/whatsapp/status")
    public ResponseEntity<?> getWhatsAppStatus(
            @RequestHeader(value = "X-Founder-Key", required = false) String providedKey,
            @RequestParam(value = "sessionId", required = false) String sessionId) {
        if (!isValidKey(providedKey)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(ApiResponse.error("Unauthorized"));
        }
        return ResponseEntity.ok(ApiResponse.success(whatsAppService.getGatewayStatus(sessionId)));
    }

    @GetMapping("/whatsapp/qr")
    public ResponseEntity<?> getWhatsAppQr(
            @RequestHeader(value = "X-Founder-Key", required = false) String providedKey,
            @RequestParam(value = "sessionId", required = false) String sessionId) {
        if (!isValidKey(providedKey)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(ApiResponse.error("Unauthorized"));
        }
        return ResponseEntity.ok(ApiResponse.success(whatsAppService.getGatewayQr(sessionId)));
    }

    @PostMapping("/whatsapp/disconnect")
    public ResponseEntity<?> disconnectWhatsApp(
            @RequestHeader(value = "X-Founder-Key", required = false) String providedKey,
            @RequestBody(required = false) Map<String, String> body) {
        if (!isValidKey(providedKey)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(ApiResponse.error("Unauthorized"));
        }
        String sessionId = body != null ? body.get("sessionId") : null;
        return ResponseEntity.ok(ApiResponse.success(whatsAppService.disconnectGateway(sessionId)));
    }

    @PostMapping("/whatsapp/test")
    public ResponseEntity<?> sendWhatsAppTest(
            @RequestHeader(value = "X-Founder-Key", required = false) String providedKey,
            @RequestBody Map<String, String> body) {
        if (!isValidKey(providedKey)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(ApiResponse.error("Unauthorized"));
        }
        String phone = body != null ? body.get("phone") : null;
        String sessionId = body != null ? body.get("sessionId") : null;
        return ResponseEntity.ok(ApiResponse.success(whatsAppService.sendTestMessage(sessionId, phone)));
    }

    @PostMapping("/whatsapp/clear-contact-session")
    public ResponseEntity<?> clearWhatsAppContactSession(
            @RequestHeader(value = "X-Founder-Key", required = false) String providedKey,
            @RequestBody Map<String, String> body) {
        if (!isValidKey(providedKey)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(ApiResponse.error("Unauthorized"));
        }
        String phone = body != null ? body.get("phone") : null;
        String sessionId = body != null ? body.get("sessionId") : null;
        return ResponseEntity.ok(ApiResponse.success(whatsAppService.clearContactSession(sessionId, phone)));
    }
}
