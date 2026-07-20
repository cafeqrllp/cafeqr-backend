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
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

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
    private final ClientSubscriptionModuleRepository moduleRepository;
    private final SubscriptionPaymentRepository paymentRepository;

    @Value("${founder.secret-key}")
    private String founderSecretKey;

    @GetMapping("/dashboard")
    public ResponseEntity<?> getFounderDashboard(
            @RequestHeader(value = "X-Founder-Key", required = false) String providedKey) {

        // ── Validate secret key ───────────────────────────────────────────────
        if (providedKey == null || !providedKey.equals(founderSecretKey)) {
            log.warn("[FounderDashboard] Unauthorized access attempt with key: {}",
                    providedKey == null ? "<none>" : providedKey.substring(0, Math.min(4, providedKey.length())) + "...");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(ApiResponse.error("Unauthorized"));
        }

        log.info("[FounderDashboard] Authorized — loading all client data");

        // ── Fetch all data ────────────────────────────────────────────────────
        List<Client> allClients = clientRepository.findAll();
        List<ClientSubscriptionModule> allModules = moduleRepository.findAll();
        List<SubscriptionPayment> allPayments = paymentRepository.findAllByOrderByCreatedAtDesc();

        // ── Deduplicate clients by UUID (guards against any DB/JPA duplicates) ─
        List<Client> uniqueClients = allClients.stream()
                .collect(Collectors.toMap(Client::getId, c -> c, (a, b) -> a))
                .values().stream().collect(Collectors.toList());

        // ── Group by clientId for O(1) lookup ─────────────────────────────────
        Map<UUID, List<ClientSubscriptionModule>> modulesByClient = allModules.stream()
                .collect(Collectors.groupingBy(ClientSubscriptionModule::getClientId));

        Map<UUID, List<SubscriptionPayment>> paymentsByClient = allPayments.stream()
                .collect(Collectors.groupingBy(SubscriptionPayment::getClientId));

        LocalDateTime now = LocalDateTime.now();

        // ── Build per-client summaries ─────────────────────────────────────────
        List<ClientSummaryDto> clientSummaries = uniqueClients.stream()
                .map(client -> {
                    List<ClientSubscriptionModule> mods = modulesByClient.getOrDefault(client.getId(), List.of());
                    List<SubscriptionPayment> payments = paymentsByClient.getOrDefault(client.getId(), List.of());

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

                    return ClientSummaryDto.builder()
                            .id(client.getId())
                            .name(client.getName())
                            .legalName(client.getLegalName())
                            .ownerName(client.getOwnerName())
                            .email(client.getEmail())
                            .phone(client.getPhone())
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
                            .whatsappNumber(client.getWhatsappNumber())
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
                            .build();
                })
                .sorted(Comparator.comparing(c -> c.getName() == null ? "" : c.getName()))
                .collect(Collectors.toList());

        // ── Aggregate stats ────────────────────────────────────────────────────
        long totalRevenue = allPayments.stream().mapToLong(SubscriptionPayment::getAmount).sum();
        int active = 0, trial = 0, expired = 0, unpaid = 0;
        for (ClientSummaryDto c : clientSummaries) {
            String status = c.getSubscriptionStatus() == null ? "" : c.getSubscriptionStatus().toUpperCase();
            switch (status) {
                case "ACTIVE" -> active++;
                case "TRIAL" -> trial++;
                case "EXPIRED" -> expired++;
                case "UNPAID" -> unpaid++;
                default -> {}
            }
        }

        FounderDashboardResponse response = FounderDashboardResponse.builder()
                .stats(FounderDashboardResponse.Stats.builder()
                        .totalClients(clientSummaries.size())
                        .activeClients(active)
                        .trialClients(trial)
                        .expiredClients(expired)
                        .unpaidClients(unpaid)
                        .totalRevenuePaise(totalRevenue)
                        .build())
                .clients(clientSummaries)
                .build();

        return ResponseEntity.ok(ApiResponse.success(response));
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
        if (providedKey == null || !providedKey.equals(founderSecretKey)) {
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
        if (providedKey == null || !providedKey.equals(founderSecretKey)) {
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
        if (providedKey == null || !providedKey.equals(founderSecretKey)) {
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
}
