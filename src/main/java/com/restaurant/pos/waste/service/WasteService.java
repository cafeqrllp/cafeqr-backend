package com.restaurant.pos.waste.service;

import com.restaurant.pos.common.exception.ResourceNotFoundException;
import com.restaurant.pos.common.tenant.TenantContext;
import com.restaurant.pos.waste.domain.WasteCategory;
import com.restaurant.pos.waste.domain.WasteLog;
import com.restaurant.pos.waste.repository.WasteCategoryRepository;
import com.restaurant.pos.waste.repository.WasteLogRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Service @RequiredArgsConstructor
public class WasteService {
    private final WasteLogRepository wasteLogRepository;
    private final WasteCategoryRepository categoryRepository;

    public List<WasteCategory> getCategories() {
        UUID clientId = TenantContext.getCurrentTenant();
        return categoryRepository.findByClientIdAndIsactiveOrderBySortOrderAsc(clientId, "Y");
    }

    @Transactional
    public WasteCategory createCategory(WasteCategory c) {
        c.setClientId(TenantContext.getCurrentTenant());
        c.setOrgId(TenantContext.getCurrentOrg());
        return categoryRepository.save(c);
    }

    @Transactional
    public WasteCategory updateCategory(UUID id, WasteCategory u) {
        WasteCategory c = findCategory(id);
        c.setName(u.getName());
        if (u.getIcon() != null) c.setIcon(u.getIcon());
        if (u.getSortOrder() != null) c.setSortOrder(u.getSortOrder());
        String newStatus = u.getIsActive();
        if (newStatus != null) c.setIsactive(newStatus);
        return categoryRepository.save(c);
    }

    @Transactional
    public void deleteCategory(UUID id) {
        WasteCategory c = findCategory(id);
        c.setIsactive("N");
        categoryRepository.save(c);
    }

    public Page<WasteLog> getLogs(LocalDateTime start, LocalDateTime end, Pageable pageable) {
        UUID clientId = TenantContext.getCurrentTenant();
        Page<WasteLog> logsPage = (start != null && end != null)
            ? wasteLogRepository.findByClientIdAndWasteDateBetweenOrderByWasteDateDesc(clientId, start, end, pageable)
            : wasteLogRepository.findByClientIdOrderByWasteDateDesc(clientId, pageable);
        Map<UUID, String> catMap = categoryRepository.findByClientIdOrderBySortOrderAsc(clientId)
            .stream().collect(java.util.stream.Collectors.toMap(WasteCategory::getId, WasteCategory::getName, (a, b) -> a));
        logsPage.forEach(w -> { if (w.getWasteCategoryId() != null) w.setCategoryName(catMap.getOrDefault(w.getWasteCategoryId(), "Other")); });
        return logsPage;
    }

    @Transactional
    public WasteLog createLog(WasteLog log) {
        log.setClientId(TenantContext.getCurrentTenant());
        log.setOrgId(TenantContext.getCurrentOrg());
        if (log.getUnitCost() != null && log.getQuantity() != null)
            log.setTotalCost(log.getUnitCost().multiply(log.getQuantity()));
        return wasteLogRepository.save(log);
    }

    @Transactional
    public WasteLog updateLog(UUID id, WasteLog u) {
        WasteLog log = findLog(id);
        log.setWasteCategoryId(u.getWasteCategoryId());
        log.setProductName(u.getProductName());
        log.setWasteReason(u.getWasteReason());
        log.setQuantity(u.getQuantity());
        log.setUnitOfMeasure(u.getUnitOfMeasure());
        log.setUnitCost(u.getUnitCost());
        log.setTotalCost(u.getUnitCost() != null && u.getQuantity() != null
            ? u.getUnitCost().multiply(u.getQuantity()) : BigDecimal.ZERO);
        log.setNotes(u.getNotes());
        log.setWasteDate(u.getWasteDate());
        return wasteLogRepository.save(log);
    }

    @Transactional
    public void deleteLog(UUID id) { wasteLogRepository.delete(findLog(id)); }

    private WasteCategory findCategory(UUID id) {
        return categoryRepository.findByIdAndClientId(id, TenantContext.getCurrentTenant())
            .orElseThrow(() -> new ResourceNotFoundException("Waste category not found: " + id));
    }

    private WasteLog findLog(UUID id) {
        return wasteLogRepository.findByIdAndClientId(id, TenantContext.getCurrentTenant())
            .orElseThrow(() -> new ResourceNotFoundException("Waste log not found: " + id));
    }

    public Map<String, Object> getAnalytics(LocalDateTime start, LocalDateTime end) {
        UUID clientId = TenantContext.getCurrentTenant();
        if (start == null) start = LocalDateTime.now().minusDays(30);
        if (end == null) end = LocalDateTime.now();
        BigDecimal totalCost = wasteLogRepository.sumTotalCostByClientIdAndDateRange(clientId, start, end);
        List<Object[]> breakdown = wasteLogRepository.getWasteBreakdown(clientId, start, end);
        List<Map<String, Object>> bl = breakdown.stream().map(r -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("reason", r[0]); m.put("count", r[1]); m.put("totalCost", r[2]);
            return m;
        }).collect(Collectors.toList());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("totalWasteCost", totalCost != null ? totalCost : BigDecimal.ZERO);
        result.put("breakdown", bl);
        result.put("period", Map.of("start", start.toString(), "end", end.toString()));
        return result;
    }
}