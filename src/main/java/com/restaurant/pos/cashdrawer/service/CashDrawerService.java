package com.restaurant.pos.cashdrawer.service;

import com.restaurant.pos.auth.domain.RoleEntity;
import com.restaurant.pos.auth.domain.User;
import com.restaurant.pos.auth.repository.UserRepository;
import com.restaurant.pos.cashdrawer.domain.CashDrawerEvent;
import com.restaurant.pos.cashdrawer.domain.CashDrawerEventStatus;
import com.restaurant.pos.cashdrawer.domain.CashDrawerTrigger;
import com.restaurant.pos.cashdrawer.dto.CashDrawerEventDto;
import com.restaurant.pos.cashdrawer.dto.CashDrawerUserSummaryDto;
import com.restaurant.pos.cashdrawer.dto.RecordCashDrawerEventRequest;
import com.restaurant.pos.cashdrawer.repository.CashDrawerEventRepository;
import com.restaurant.pos.client.domain.Terminal;
import com.restaurant.pos.client.repository.TerminalRepository;
import com.restaurant.pos.common.context.ContextProvider;
import com.restaurant.pos.common.dto.ConfigurationDto;
import com.restaurant.pos.common.exception.BusinessException;
import com.restaurant.pos.common.service.SystemConfigurationService;
import jakarta.persistence.criteria.Predicate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class CashDrawerService {

    private static final int MAX_PAGE_SIZE = 200;

    private final CashDrawerEventRepository repository;
    private final UserRepository userRepository;
    private final TerminalRepository terminalRepository;
    private final SystemConfigurationService configurationService;
    private final ContextProvider contextProvider;

    // ── Record ────────────────────────────────────────────────────────────

    @Transactional
    public CashDrawerEventDto record(RecordCashDrawerEventRequest request) {
        UUID clientId = contextProvider.getCurrentTenant();
        UUID orgId = contextProvider.getCurrentOrg();

        String clientEventId = trimToNull(request.getClientEventId());
        if (clientId != null && clientEventId != null) {
            Optional<CashDrawerEvent> existing = repository.findByClientIdAndClientEventId(clientId, clientEventId);
            if (existing.isPresent()) {
                return toDto(existing.get(), Map.of());
            }
        }

        User user = currentUser();
        RoleEntity role = user != null ? user.getRoleEntity() : null;
        if (request.getTriggerType() == null) {
            throw new BusinessException("triggerType is required");
        }
        // The device has already kicked the drawer by the time this arrives, so rejecting the event
        // would only hide it from the audit trail. Record it and flag the rule it broke.
        String violation = policyViolation(request, role, clientId, orgId);

        CashDrawerEvent event = CashDrawerEvent.builder()
                .terminalId(contextProvider.getCurrentTerminal())
                .userId(user != null ? user.getId() : contextProvider.getCurrentUserId())
                .userName(displayName(user))
                .roleName(role != null ? role.getName() : null)
                .triggerType(request.getTriggerType())
                .reason(trimToNull(request.getReason()))
                .orderId(request.getOrderId())
                .orderNo(trimToNull(request.getOrderNo()))
                .paymentMethod(upperOrNull(request.getPaymentMethod()))
                .amount(request.getAmount())
                .printerName(trimToNull(request.getPrinterName()))
                .transport(trimToNull(request.getTransport()))
                .status(request.getStatus())
                .errorMessage(truncate(request.getErrorMessage(), 500))
                .deviceEventAt(request.getDeviceEventAt())
                .clientEventId(clientEventId)
                .deviceLabel(trimToNull(request.getDeviceLabel()))
                .policyViolation(violation)
                .build();
        event.setClientId(clientId);
        event.setOrgId(orgId);

        CashDrawerEvent saved = repository.save(event);
        if (violation != null) {
            log.warn("Cash drawer event outside policy | violation={} | trigger={} | status={} | user={} | terminal={}",
                    violation, saved.getTriggerType(), saved.getStatus(), saved.getUserId(), saved.getTerminalId());
        }
        log.info("Cash drawer event | trigger={} | status={} | user={} | terminal={} | order={}",
                saved.getTriggerType(), saved.getStatus(), saved.getUserId(), saved.getTerminalId(), saved.getOrderNo());
        return toDto(saved, Map.of());
    }

    // ── Query ─────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public Page<CashDrawerEventDto> search(String from, String to, UUID orgId, UUID userId, UUID terminalId,
                                           CashDrawerTrigger trigger, CashDrawerEventStatus status, int page, int size) {
        assertMayViewHistory();
        Specification<CashDrawerEvent> spec = buildSpec(from, to, orgId, userId, terminalId, trigger, status);
        int safeSize = Math.max(1, Math.min(size, MAX_PAGE_SIZE));
        Page<CashDrawerEvent> result = repository.findAll(spec,
                PageRequest.of(Math.max(0, page), safeSize, Sort.by(Sort.Direction.DESC, "createdAt")));
        Map<UUID, String> terminals = terminalNames(result.getContent());
        return result.map(e -> toDto(e, terminals));
    }

    @Transactional(readOnly = true)
    public List<CashDrawerUserSummaryDto> summary(String from, String to, UUID orgId, UUID terminalId) {
        assertMayViewHistory();
        List<CashDrawerEvent> events = repository.findAll(buildSpec(from, to, orgId, null, terminalId, null, null));
        Map<String, CashDrawerUserSummaryDto> byUser = new LinkedHashMap<>();
        for (CashDrawerEvent e : events) {
            String key = e.getUserId() != null ? e.getUserId().toString() : "unknown";
            CashDrawerUserSummaryDto row = byUser.computeIfAbsent(key, k -> CashDrawerUserSummaryDto.builder()
                    .userId(e.getUserId())
                    .userName(e.getUserName())
                    .build());
            row.setTotal(row.getTotal() + 1);
            if (e.getStatus() == CashDrawerEventStatus.FAILED) {
                row.setFailed(row.getFailed() + 1);
            }
            switch (e.getTriggerType()) {
                case SALE_SETTLE -> row.setSaleSettle(row.getSaleSettle() + 1);
                case ORDER_SETTLE -> row.setOrderSettle(row.getOrderSettle() + 1);
                case MANUAL -> row.setManual(row.getManual() + 1);
                case TEST -> row.setTest(row.getTest() + 1);
            }
        }
        return byUser.values().stream()
                .sorted(Comparator.comparingLong(CashDrawerUserSummaryDto::getManual).reversed()
                        .thenComparing(Comparator.comparingLong(CashDrawerUserSummaryDto::getTotal).reversed()))
                .collect(Collectors.toList());
    }

    // ── Permissions ───────────────────────────────────────────────────────

    static final String VIOLATION_NOT_PERMITTED = "NOT_PERMITTED";
    static final String VIOLATION_REASON_MISSING = "REASON_MISSING";

    /** Which rule this open broke, or null when it is within policy. */
    private String policyViolation(RecordCashDrawerEventRequest request, RoleEntity role, UUID clientId, UUID orgId) {
        CashDrawerTrigger trigger = request.getTriggerType();
        if (!isAdministrator()) {
            boolean permitted = switch (trigger) {
                case MANUAL, TEST -> role != null && Boolean.TRUE.equals(role.getCanOpenCashDrawer());
                case SALE_SETTLE, ORDER_SETTLE -> role == null || !Boolean.FALSE.equals(role.getCanAutoOpenCashDrawer());
            };
            if (!permitted) {
                return VIOLATION_NOT_PERMITTED;
            }
        }
        if (trigger == CashDrawerTrigger.MANUAL
                && request.getStatus() == CashDrawerEventStatus.SENT
                && trimToNull(request.getReason()) == null
                && isReasonRequired(clientId, orgId)) {
            return VIOLATION_REASON_MISSING;
        }
        return null;
    }

    private void assertMayViewHistory() {
        if (isAdministrator()) {
            return;
        }
        User user = currentUser();
        RoleEntity role = user != null ? user.getRoleEntity() : null;
        if (role == null || !Boolean.TRUE.equals(role.getCanViewCashDrawerHistory())) {
            throw new AccessDeniedException("Role is not allowed to view cash drawer history");
        }
    }

    private boolean isAdministrator() {
        return contextProvider.isSuperAdmin() || contextProvider.hasRole("SUPER_ADMIN") || contextProvider.hasRole("ADMIN");
    }

    private boolean isReasonRequired(UUID clientId, UUID orgId) {
        try {
            ConfigurationDto config = configurationService.getConfigurationForClientAndBranch(clientId, orgId);
            return config == null || config.isCashDrawerManualReasonRequired();
        } catch (Exception ex) {
            log.warn("Could not resolve cash drawer configuration; requiring reason | client={} | org={}", clientId, orgId, ex);
            return true;
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private Specification<CashDrawerEvent> buildSpec(String from, String to, UUID orgId, UUID userId, UUID terminalId,
                                                     CashDrawerTrigger trigger, CashDrawerEventStatus status) {
        UUID clientId = contextProvider.getCurrentTenant();
        UUID effectiveOrg = orgId != null ? orgId : contextProvider.getCurrentOrg();
        ZoneId zone = Optional.ofNullable(contextProvider.getCurrentTimezone()).orElse(ZoneOffset.UTC);
        LocalDateTime fromUtc = parseBound(from, zone, false);
        LocalDateTime toUtcExclusive = parseBound(to, zone, true);

        return (root, query, cb) -> {
            List<Predicate> p = new ArrayList<>();
            if (clientId != null) p.add(cb.equal(root.get("clientId"), clientId));
            if (effectiveOrg != null) p.add(cb.equal(root.get("orgId"), effectiveOrg));
            if (userId != null) p.add(cb.equal(root.get("userId"), userId));
            if (terminalId != null) p.add(cb.equal(root.get("terminalId"), terminalId));
            if (trigger != null) p.add(cb.equal(root.get("triggerType"), trigger));
            if (status != null) p.add(cb.equal(root.get("status"), status));
            if (fromUtc != null) p.add(cb.greaterThanOrEqualTo(root.get("createdAt"), fromUtc));
            if (toUtcExclusive != null) p.add(cb.lessThan(root.get("createdAt"), toUtcExclusive));
            return cb.and(p.toArray(new Predicate[0]));
        };
    }

    private static LocalDateTime toUtc(java.time.Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    /**
     * Accepts an exact instant ("2026-11-11T18:30:00Z" / with offset) or a business-calendar date ("2026-11-11").
     * Upper bounds are returned exclusive: a date covers the whole day, an instant covers the whole minute it falls in.
     */
    static LocalDateTime parseBound(String raw, ZoneId zone, boolean upper) {
        if (raw == null || raw.isBlank()) return null;
        String v = raw.trim();
        try {
            if (v.length() == 10) {
                LocalDate day = LocalDate.parse(v);
                return toUtc((upper ? day.plusDays(1) : day).atStartOfDay(zone).toInstant());
            }
            java.time.Instant instant = java.time.OffsetDateTime.parse(v).toInstant();
            if (upper) instant = instant.truncatedTo(java.time.temporal.ChronoUnit.MINUTES).plusSeconds(60); // pickers are minute-precise: include the whole minute
            return toUtc(instant);
        } catch (java.time.format.DateTimeParseException ex) {
            throw new BusinessException("Invalid date/time filter: " + v);
        }
    }

    private Map<UUID, String> terminalNames(Collection<CashDrawerEvent> events) {
        Set<UUID> ids = events.stream().map(CashDrawerEvent::getTerminalId).filter(Objects::nonNull).collect(Collectors.toSet());
        if (ids.isEmpty()) return Map.of();
        return terminalRepository.findAllById(ids).stream()
                .collect(Collectors.toMap(Terminal::getId, t -> Objects.toString(t.getName(), ""), (a, b) -> a));
    }

    private User currentUser() {
        UUID userId = contextProvider.getCurrentUserId();
        return userId != null ? userRepository.findById(userId).orElse(null) : null;
    }

    private static String displayName(User user) {
        if (user == null) return null;
        String name = (Objects.toString(user.getFirstName(), "") + " " + Objects.toString(user.getLastName(), "")).trim();
        return name.isEmpty() ? user.getEmail() : name;
    }

    private CashDrawerEventDto toDto(CashDrawerEvent e, Map<UUID, String> terminals) {
        return CashDrawerEventDto.builder()
                .id(e.getId())
                .orgId(e.getOrgId())
                .terminalId(e.getTerminalId())
                .terminalName(e.getTerminalId() != null ? terminals.get(e.getTerminalId()) : null)
                .userId(e.getUserId())
                .userName(e.getUserName())
                .roleName(e.getRoleName())
                .triggerType(e.getTriggerType())
                .reason(e.getReason())
                .orderId(e.getOrderId())
                .orderNo(e.getOrderNo())
                .paymentMethod(e.getPaymentMethod())
                .amount(e.getAmount())
                .printerName(e.getPrinterName())
                .transport(e.getTransport())
                .status(e.getStatus())
                .errorMessage(e.getErrorMessage())
                .deviceLabel(e.getDeviceLabel())
                .policyViolation(e.getPolicyViolation())
                .recordedAt(e.getCreatedAt())
                .deviceEventAt(e.getDeviceEventAt())
                .build();
    }

    private static String trimToNull(String value) {
        if (value == null) return null;
        String t = value.trim();
        return t.isEmpty() ? null : t;
    }

    private static String upperOrNull(String value) {
        String t = trimToNull(value);
        return t == null ? null : t.toUpperCase(Locale.ROOT);
    }

    private static String truncate(String value, int max) {
        String t = trimToNull(value);
        return t == null || t.length() <= max ? t : t.substring(0, max);
    }
}
