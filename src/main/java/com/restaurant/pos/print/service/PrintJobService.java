package com.restaurant.pos.print.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.restaurant.pos.client.domain.Client;
import com.restaurant.pos.client.domain.Organization;
import com.restaurant.pos.client.repository.ClientRepository;
import com.restaurant.pos.client.repository.OrganizationRepository;
import com.restaurant.pos.common.dto.ConfigurationDto;
import com.restaurant.pos.common.exception.BusinessException;
import com.restaurant.pos.common.service.SystemConfigurationService;
import com.restaurant.pos.common.tenant.TenantContext;
import com.restaurant.pos.common.context.TimezoneResolver;
import com.restaurant.pos.order.domain.Order;
import com.restaurant.pos.order.domain.OrderLine;
import com.restaurant.pos.order.repository.OrderRepository;
import com.restaurant.pos.print.domain.PrintJob;
import com.restaurant.pos.print.domain.PrintJobKind;
import com.restaurant.pos.print.domain.PrintJobStatus;
import com.restaurant.pos.print.repository.PrintJobAttemptRepository;
import com.restaurant.pos.print.repository.PrintJobRepository;
import com.restaurant.pos.purchasing.domain.Customer;
import com.restaurant.pos.purchasing.repository.CustomerRepository;
import com.restaurant.pos.order.dto.OrderCustomerDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import java.util.ArrayList;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class PrintJobService {

    private final PrintJobRepository printJobRepository;
    private final PrintJobAttemptRepository printJobAttemptRepository;
    private final OrderRepository orderRepository;
    private final ObjectMapper objectMapper;
    private final ClientRepository clientRepository;
    private final OrganizationRepository organizationRepository;
    private final SystemConfigurationService systemConfigurationService;
    private final CustomerRepository customerRepository;
    private final TimezoneResolver timezoneResolver;

    @Transactional
    public PrintJob enqueueOrderJob(UUID orderId, String jobKind) {
        UUID clientId = TenantContext.getCurrentTenant();
        Order order = orderRepository.findByIdAndClientId(orderId, clientId)
                .orElseThrow(() -> new BusinessException("Order not found"));
        if ("VOID".equalsIgnoreCase(order.getOrderStatus()) || "N".equalsIgnoreCase(order.getIsactive())) {
            String baseOrderNo = order.getOrderNo();
            if (baseOrderNo != null && baseOrderNo.contains("_VOID_")) {
                baseOrderNo = baseOrderNo.substring(0, baseOrderNo.indexOf("_VOID_"));
            }
            java.util.Optional<Order> activeOrder = orderRepository.findByOrderNoAndClientId(baseOrderNo, clientId);
            if (activeOrder.isPresent()) {
                order = activeOrder.get();
            }
        }
        return enqueueForOrder(order, parseKind(jobKind), "manual");
    }

    @Transactional
    public PrintJob enqueueForOrder(Order order, PrintJobKind kind, String reason) {
        if (kind == PrintJobKind.KOT) {
            ConfigurationDto config = systemConfigurationService.getEffectiveConfigurationForBranch(order.getOrgId());
            if (config == null || !config.isSendToKitchenEnabled()) {
                log.info("enqueueForOrder: skipping KOT print job because KOT module/feature is disabled or expired for branch={}", order.getOrgId());
                return null;
            }
        }
        UUID clientId = order.getClientId() != null ? order.getClientId() : TenantContext.getCurrentTenant();
        String dedupeKey = buildDedupeKey(order, kind, reason);
        log.info("enqueueForOrder: orderId={}, kind={}, reason={}, dedupeKey={}", order.getId(), kind, reason, dedupeKey);
        return createJob(order, kind, reason, dedupeKey, clientId);
    }

    @Transactional
    public List<PrintJob> claimJobs(int limit) {
        UUID clientId = TenantContext.getCurrentTenant();
        if (clientId == null) {
            return List.of();
        }
        UUID orgId = TenantContext.getCurrentOrg();
        UUID terminalId = TenantContext.getCurrentTerminal();
        int boundedLimit = Math.max(1, Math.min(limit <= 0 ? 5 : limit, 20));

        List<PrintJob> jobs = printJobRepository.findClaimable(
                clientId,
                orgId,
                List.of(PrintJobStatus.PENDING, PrintJobStatus.RETRY),
                List.of(PrintJobKind.KOT, PrintJobKind.BILL),
                LocalDateTime.now(),
                PageRequest.of(0, boundedLimit)
        );

        LocalDateTime now = LocalDateTime.now();
        for (PrintJob job : jobs) {
            job.setStatus(PrintJobStatus.CLAIMED);
            job.setClaimedByTerminalId(terminalId);
            job.setClaimedAt(now);
            job.setAttempts((job.getAttempts() == null ? 0 : job.getAttempts()) + 1);
        }
        return printJobRepository.saveAll(jobs);
    }

    @Transactional
    public PrintJob markPrinted(UUID id) {
        PrintJob job = getJob(id);
        markPrintedFields(job, LocalDateTime.now());
        return printJobRepository.save(job);
    }

    @Transactional
    public List<PrintJob> markPrintedForOrder(UUID orderId, String jobKind) {
        UUID clientId = TenantContext.getCurrentTenant();
        if (clientId == null || orderId == null) {
            return List.of();
        }

        List<PrintJob> jobs = printJobRepository.findByClientIdAndOrderIdAndJobKindAndStatusInOrderByCreatedAtDesc(
                clientId,
                orderId,
                parseKind(jobKind),
                List.of(
                        PrintJobStatus.PENDING,
                        PrintJobStatus.CLAIMED,
                        PrintJobStatus.LEASED,
                        PrintJobStatus.LOCAL_QUEUED,
                        PrintJobStatus.SPOOLING,
                        PrintJobStatus.RETRY,
                        PrintJobStatus.RETRY_WAIT,
                        PrintJobStatus.HELD_AMBIGUOUS,
                        PrintJobStatus.FAILED
                )
        );

        LocalDateTime now = LocalDateTime.now();
        for (PrintJob job : jobs) {
            markPrintedFields(job, now);
        }
        return printJobRepository.saveAll(jobs);
    }

    @Transactional
    public PrintJob markFailed(UUID id, String message) {
        PrintJob job = getJob(id);
        job.setStatus(PrintJobStatus.FAILED);
        job.setErrorMessage(message == null || message.isBlank() ? "Printing failed" : message);
        job.setNextAttemptAt(null);
        return printJobRepository.save(job);
    }

    @Transactional
    public PrintJob retry(UUID id) {
        PrintJob job = getJob(id);
        job.setStatus(PrintJobStatus.RETRY);
        job.setNextAttemptAt(LocalDateTime.now());
        job.setErrorMessage(null);
        return printJobRepository.save(job);
    }

    @Transactional(readOnly = true)
    public List<PrintJob> recent() {
        UUID clientId = TenantContext.getCurrentTenant();
        if (clientId == null) {
            return List.of();
        }
        return printJobRepository.findRecentValid(clientId);
    }

    public Map<String, Object> payload(PrintJob job) {
        try {
            if (job == null || job.getPayloadJson() == null || job.getPayloadJson().isBlank()) {
                return Map.of();
            }
            return objectMapper.readValue(job.getPayloadJson(), new TypeReference<>() {});
        } catch (Exception ex) {
            return Map.of();
        }
    }

    public Map<String, Object> describe(PrintJob job) {
        Map<String, Object> dto = new LinkedHashMap<>();
        dto.put("id", job.getId());
        dto.put("orderId", job.getOrderId());
        dto.put("offlineOperationId", job.getOfflineOperationId());
        dto.put("sourceOperationId", job.getSourceOperationId());
        dto.put("sourceTerminalId", job.getSourceTerminalId());
        dto.put("targetTerminalId", job.getTargetTerminalId());
        dto.put("claimedByTerminalId", job.getClaimedByTerminalId());
        dto.put("leasedByStationId", job.getLeasedByStationId());
        dto.put("leaseToken", job.getLeaseToken());
        dto.put("leaseExpiresAt", job.getLeaseExpiresAt());
        dto.put("jobKind", job.getJobKind() == null ? "bill" : job.getJobKind().name().toLowerCase());
        dto.put("status", job.getStatus() == null ? "PENDING" : job.getStatus().name());
        dto.put("attempts", job.getAttempts() == null ? 0 : job.getAttempts());
        dto.put("errorMessage", job.getErrorMessage());
        dto.put("failureCode", job.getFailureCode());
        dto.put("spoolJobId", job.getSpoolJobId());
        dto.put("printerProfileId", job.getPrinterProfileId());
        dto.put("routeId", job.getRouteId());
        dto.put("outputFormat", job.getOutputFormat());
        dto.put("ambiguous", Boolean.TRUE.equals(job.getAmbiguous()));
        dto.put("payload", payload(job));
        dto.put("createdAt", job.getCreatedAt());
        dto.put("updatedAt", job.getUpdatedAt());
        dto.put("attemptHistory", printJobAttemptRepository.findAllByPrintJobIdOrderByCreatedAtAsc(job.getId()).stream()
                .map(attempt -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("attempt", attempt.getAttemptNumber());
                    item.put("status", attempt.getStatus());
                    item.put("message", attempt.getMessage());
                    item.put("failureCode", attempt.getFailureCode());
                    item.put("spoolJobId", attempt.getSpoolJobId());
                    item.put("createdAt", attempt.getCreatedAt());
                    return item;
                })
                .toList());
        return dto;
    }

    private PrintJob createJob(Order order, PrintJobKind kind, String reason, String dedupeKey, UUID clientId) {
        try {
            Map<String, Object> payloadMap = new LinkedHashMap<>();
            payloadMap.put("order", orderSnapshot(order, kind));
            payloadMap.put("jobKind", kind.name().toLowerCase());
            payloadMap.put("reason", reason == null ? "auto" : reason);
            payloadMap.put("restaurant", buildRestaurantDetails(clientId, order.getOrgId()));
            String payloadJson = objectMapper.writeValueAsString(payloadMap);

            UUID sourceTermId = order.getSourceTerminalId() != null ? order.getSourceTerminalId() : order.getTerminalId();
            if (sourceTermId == null) {
                sourceTermId = TenantContext.getCurrentTerminal();
            }

            PrintJob job = PrintJob.builder()
                    .orderId(order.getId())
                    .offlineOperationId(order.getSourceOfflineId())
                    .sourceOperationId(order.getSourceOperationId())
                    .sourceTerminalId(sourceTermId)
                    .sourceDeviceId(order.getSourceDeviceId())
                    .jobKind(kind)
                    .status(PrintJobStatus.PENDING)
                    .dedupeKey(dedupeKey)
                    .payloadJson(payloadJson)
                    .build();
            job.setClientId(clientId);
            job.setOrgId(order.getOrgId());
            PrintJob saved = printJobRepository.save(job);
            return saved;
        } catch (DataIntegrityViolationException ex) {
            return printJobRepository.findByClientIdAndDedupeKey(clientId, dedupeKey)
                    .orElseThrow(() -> ex);
        } catch (Exception ex) {
            log.warn("Unable to create print job for order {}", order.getId(), ex);
            throw new BusinessException("Unable to create print job");
        }
    }

    @Transactional
    public PrintJob enqueueKotEditJob(Order order, List<OrderLine> addedLines, List<OrderLine> removedLines, String reason) {
        ConfigurationDto config = systemConfigurationService.getEffectiveConfigurationForBranch(order.getOrgId());
        if (config == null || !config.isSendToKitchenEnabled()) {
            log.info("enqueueKotEditJob: skipping KOT edit print job because KOT module/feature is disabled or expired for branch={}", order.getOrgId());
            return null;
        }
        UUID clientId = order.getClientId() != null ? order.getClientId() : TenantContext.getCurrentTenant();
        String dedupeKey = buildDedupeKey(order, PrintJobKind.KOT, reason);
        log.info("enqueueKotEditJob: orderId={}, addedLines={}, removedLines={}, dedupeKey={}", order.getId(), (addedLines != null ? addedLines.size() : 0), (removedLines != null ? removedLines.size() : 0), dedupeKey);
        return createKotEditJob(order, addedLines, removedLines, reason, dedupeKey, clientId);
    }

    private PrintJob createKotEditJob(Order order, List<OrderLine> addedLines, List<OrderLine> removedLines, String reason, String dedupeKey, UUID clientId) {
        try {
            Map<String, Object> orderSnap = orderSnapshot(order, PrintJobKind.KOT);
            List<Map<String, Object>> addedSnaps = addedLines == null
                    ? List.of()
                    : addedLines.stream().map(line -> this.lineSnapshot(line, PrintJobKind.KOT)).toList();
            orderSnap.put("lines", addedSnaps);
            orderSnap.put("orderLines", addedSnaps);
            orderSnap.put("order_items", addedSnaps);

            List<Map<String, Object>> removedSnaps = removedLines == null
                    ? List.of()
                    : removedLines.stream().map(line -> this.lineSnapshot(line, PrintJobKind.KOT)).toList();
            orderSnap.put("removed_items", removedSnaps);
            orderSnap.put("removedItems", removedSnaps);
            orderSnap.put("is_edited", true);
            orderSnap.put("isEdited", true);

            Map<String, Object> payloadMap = new LinkedHashMap<>();
            payloadMap.put("order", orderSnap);
            payloadMap.put("jobKind", "kot");
            payloadMap.put("reason", reason == null ? "auto" : reason);
            payloadMap.put("restaurant", buildRestaurantDetails(clientId, order.getOrgId()));
            String payloadJson = objectMapper.writeValueAsString(payloadMap);

            UUID sourceTermId = order.getSourceTerminalId() != null ? order.getSourceTerminalId() : order.getTerminalId();
            if (sourceTermId == null) {
                sourceTermId = TenantContext.getCurrentTerminal();
            }

            PrintJob job = PrintJob.builder()
                    .orderId(order.getId())
                    .offlineOperationId(order.getSourceOfflineId())
                    .sourceOperationId(order.getSourceOperationId())
                    .sourceTerminalId(sourceTermId)
                    .sourceDeviceId(order.getSourceDeviceId())
                    .jobKind(PrintJobKind.KOT)
                    .status(PrintJobStatus.PENDING)
                    .dedupeKey(dedupeKey)
                    .payloadJson(payloadJson)
                    .build();
            job.setClientId(clientId);
            job.setOrgId(order.getOrgId());
            PrintJob saved = printJobRepository.save(job);
            log.info("createKotEditJob: successfully created PrintJob {} for order {} with dedupeKey {}", saved.getId(), order.getId(), dedupeKey);
            return saved;
        } catch (DataIntegrityViolationException ex) {
            return printJobRepository.findByClientIdAndDedupeKey(clientId, dedupeKey)
                    .orElseThrow(() -> ex);
        } catch (Exception ex) {
            log.warn("Unable to create print job for order {}", order.getId(), ex);
            throw new BusinessException("Unable to create print job");
        }
    }

    private PrintJob getJob(UUID id) {
        UUID clientId = TenantContext.getCurrentTenant();
        return printJobRepository.findById(id)
                .filter(job -> clientId == null || clientId.equals(job.getClientId()))
                .orElseThrow(() -> new BusinessException("Print job not found"));
    }

    private void markPrintedFields(PrintJob job, LocalDateTime now) {
        job.setStatus(PrintJobStatus.PRINTED);
        job.setPrintedAt(now);
        job.setErrorMessage(null);
        job.setNextAttemptAt(null);
    }

    private Map<String, Object> orderSnapshot(Order order, PrintJobKind kind) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", order.getId());
        out.put("orgId", order.getOrgId());
        out.put("org_id", order.getOrgId());
        out.put("timezone", timezoneResolver.resolveTimezone(order.getClientId(), order.getOrgId()).getId());
        out.put("orderNo", order.getOrderNo());
        out.put("order_no", order.getOrderNo());
        out.put("invoiceNo", order.getInvoiceNo());
        out.put("invoice_no", order.getInvoiceNo());
        out.put("dailyBillNo", order.getDailyBillNo());
        out.put("daily_bill_no", order.getDailyBillNo());
        out.put("paymentNo", order.getPaymentNo());
        out.put("payment_no", order.getPaymentNo());
        out.put("orderStatus", order.getOrderStatus());
        out.put("order_status", order.getOrderStatus());
        out.put("paymentStatus", order.getPaymentStatus());
        out.put("payment_status", order.getPaymentStatus());
        out.put("orderType", order.getOrderType());
        out.put("order_type", order.getOrderType());
        out.put("fulfillmentType", order.getFulfillmentType());
        out.put("fulfillment_type", order.getFulfillmentType());
        out.put("tableNumber", order.getTableNumber());
        out.put("table_number", order.getTableNumber());
        out.put("tableId", order.getTableId());
        out.put("table_id", order.getTableId());
        out.put("orderDate", order.getOrderDate());
        out.put("order_date", order.getOrderDate());
        out.put("totalAmount", order.getTotalAmount());
        out.put("total_amount", order.getTotalAmount());
        out.put("grandTotal", order.getGrandTotal());
        out.put("grand_total", order.getGrandTotal());
        out.put("totalTaxAmount", order.getTotalTaxAmount());
        out.put("total_tax_amount", order.getTotalTaxAmount());
        out.put("totalDiscountAmount", order.getTotalDiscountAmount());
        out.put("total_discount_amount", order.getTotalDiscountAmount());

        // Add customer fields so receipt & KOT printing can show customer details
        populateCustomerDetailsIfNeeded(order, out);

        // Add description and instructions fields
        out.put("description", order.getDescription());
        out.put("specialInstructions", order.getDescription());
        out.put("special_instructions", order.getDescription());
        out.put("instructions", order.getDescription());

        List<Map<String, Object>> lines = order.getLines() == null
                ? List.of()
                : order.getLines().stream().map(line -> this.lineSnapshot(line, kind)).toList();
        out.put("lines", lines);
        out.put("orderLines", lines);
        out.put("order_items", lines);
        return out;
    }

    private Map<String, Object> lineSnapshot(OrderLine line, PrintJobKind kind) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", line.getId());
        out.put("productId", line.getProductId());
        out.put("product_id", line.getProductId());
        out.put("variantId", line.getVariantId());
        out.put("variant_id", line.getVariantId());
        out.put("productName", line.getProductName());
        out.put("product_name", line.getProductName());
        out.put("name", line.getProductName());
        out.put("categoryName", line.getCategoryName());
        out.put("category_name", line.getCategoryName());
        out.put("category", line.getCategoryName());
        out.put("quantity", line.getQuantity());
        out.put("qty", line.getQuantity());
        out.put("unitPrice", line.getUnitPrice());
        out.put("unit_price", line.getUnitPrice());
        out.put("price", line.getUnitPrice());
        out.put("lineTotal", line.getLineTotal());
        out.put("line_total", line.getLineTotal());
        out.put("taxAmount", line.getTaxAmount());
        out.put("tax_amount", line.getTaxAmount());
        out.put("discountAmount", line.getDiscountAmount());
        out.put("discount_amount", line.getDiscountAmount());
        out.put("isPackagedGood", line.getIsPackagedGood());
        out.put("is_packaged_good", line.getIsPackagedGood());
        // Per-item kitchen notes — shown below item name on KOT only if kind is KOT and note is not empty
        if (kind == PrintJobKind.KOT && line.getDescription() != null && !line.getDescription().trim().isEmpty()) {
            out.put("description", line.getDescription());
            out.put("notes", line.getDescription());
            out.put("line_notes", line.getDescription());
            out.put("itemNotes", line.getDescription());
        }
        return out;
    }

    private String buildDedupeKey(Order order, PrintJobKind kind, String reason) {
        String revision = String.valueOf(order.getRevisionNumber() == null ? 0 : order.getRevisionNumber());
        String base = order.getId() + ":" + kind.name() + ":" + revision;
        if ("manual".equalsIgnoreCase(reason)) {
            return base + ":manual:" + UUID.randomUUID();
        }
        if ("edit".equalsIgnoreCase(reason)) {
            return base + ":edit:" + UUID.randomUUID();
        }
        return base + ":auto";
    }

    private PrintJobKind parseKind(String value) {
        if (value == null || value.isBlank()) {
            return PrintJobKind.BILL;
        }
        if ("kot".equalsIgnoreCase(value)) {
            return PrintJobKind.KOT;
        }
        if ("invoice".equalsIgnoreCase(value)) {
            return PrintJobKind.INVOICE;
        }
        return PrintJobKind.BILL;
    }

    /**
     * Gathers restaurant profile details from the Client entity, Organization entity,
     * and SystemConfiguration so the print service can render fully formatted prints.
     * Keys are aligned with what the C# DocumentRenderer.cs expects.
     */
    private Map<String, Object> buildRestaurantDetails(UUID clientId, UUID orgId) {
        Map<String, Object> details = new HashMap<>();
        try {
            // Add timezone explicitly to the print payload so the renderer knows the branch's local time
            details.put("timezone", timezoneResolver.resolveTimezone(clientId, orgId).getId());

            // Client-level defaults
            if (clientId != null) {
                clientRepository.findById(clientId).ifPresent(client -> {
                    details.put("restaurantName", client.getName());
                    details.put("restaurant_name", client.getName());
                    details.put("name", client.getName());
                    details.put("phone", client.getPhone());
                    details.put("shipping_phone", client.getPhone());
                    details.put("shipping_address_line1", client.getAddress());
                    details.put("gstin", client.getGstNumber());
                    details.put("gstNumber", client.getGstNumber());
                    details.put("fssai_license", client.getFssaiNumber());
                    details.put("fssaiNumber", client.getFssaiNumber());
                    details.put("fssai", client.getFssaiNumber());
                });
            }

            // Branch-level overrides (Organization fields take precedence)
            if (orgId != null) {
                organizationRepository.findById(orgId).ifPresent(org -> {
                    if (org.getName() != null && !org.getName().isBlank()) {
                        details.put("restaurantName", org.getName());
                        details.put("restaurant_name", org.getName());
                        details.put("name", org.getName());
                    }
                    if (org.getAddress() != null && !org.getAddress().isBlank()) {
                        details.put("shipping_address_line1", org.getAddress());
                    }
                    if (org.getPhone() != null && !org.getPhone().isBlank()) {
                        details.put("phone", org.getPhone());
                        details.put("shipping_phone", org.getPhone());
                    }
                    if (org.getGstin() != null && !org.getGstin().isBlank()) {
                        details.put("gstin", org.getGstin());
                        details.put("gstNumber", org.getGstin());
                    }
                });
            }

            // System configuration (billFooter, etc.)
            try {
                ConfigurationDto config = systemConfigurationService
                        .getConfigurationForClientAndBranch(clientId, orgId);
                if (config != null) {
                    if (config.getBillFooter() != null) {
                        details.put("billFooter", config.getBillFooter());
                        details.put("bill_footer", config.getBillFooter());
                    }
                    if (config.getPrintLogoBitmap() != null) {
                        details.put("print_logo_bitmap", config.getPrintLogoBitmap());
                        details.put("printLogoBitmap", config.getPrintLogoBitmap());
                    }
                    if (config.getPrintLogoCols() != null) {
                        details.put("print_logo_cols", config.getPrintLogoCols());
                        details.put("printLogoCols", config.getPrintLogoCols());
                    }
                    if (config.getPrintLogoRows() != null) {
                        details.put("print_logo_rows", config.getPrintLogoRows());
                        details.put("printLogoRows", config.getPrintLogoRows());
                    }
                    if (config.getTaxLabelGlobal() != null) {
                        details.put("taxLabelGlobal", config.getTaxLabelGlobal());
                        details.put("tax_label_global", config.getTaxLabelGlobal());
                    }
                }
            } catch (Exception configEx) {
                log.debug("Could not fetch system config for restaurant details: {}", configEx.getMessage());
            }
        } catch (Exception ex) {
            log.warn("Failed to build restaurant details for clientId={}, orgId={}: {}", clientId, orgId, ex.getMessage());
        }
        return details;
    }

    private void populateCustomerDetailsIfNeeded(Order order, Map<String, Object> out) {
        String name = order.getCustomerName();
        String phone = order.getCustomerPhone();
        List<Map<String, Object>> customersList = new ArrayList<>();

        if ((name == null || name.isBlank()) && (phone == null || phone.isBlank())) {
            // Fetch from repository
            try {
                UUID clientId = order.getClientId() != null ? order.getClientId() : TenantContext.getCurrentTenant();
                if (clientId != null && order.getId() != null) {
                    String orderNeedleStr = "[{\"orderId\":\"" + order.getId() + "\"}]";
                    String primaryNeedleStr = "[{\"orderId\":\"" + order.getId() + "\",\"isPrimary\":true}]";
                    List<Customer> linked = customerRepository.findByClientIdAndOrderLink(clientId, orderNeedleStr, primaryNeedleStr);
                    if (linked.isEmpty() && order.getCustomerId() != null) {
                        customerRepository.findByIdAndClientId(order.getCustomerId(), clientId).ifPresent(linked::add);
                    }
                    
                    if (!linked.isEmpty()) {
                        Customer primary = null;
                        // Find primary customer
                        for (Customer c : linked) {
                            boolean isPrimary = false;
                            if (c.getOrderLinks() != null) {
                                for (Customer.OrderLink link : c.getOrderLinks()) {
                                    if (order.getId().equals(link.getOrderId()) && Boolean.TRUE.equals(link.getIsPrimary())) {
                                        isPrimary = true;
                                        break;
                                    }
                                }
                            }
                            if (isPrimary) {
                                primary = c;
                                break;
                            }
                        }
                        if (primary == null) {
                            primary = linked.get(0);
                        }
                        
                        name = primary.getName();
                        phone = primary.getPhone();
                        
                        for (Customer c : linked) {
                            boolean isPrimary = (c == primary);
                            Map<String, Object> cmap = new LinkedHashMap<>();
                            cmap.put("id", c.getId());
                            cmap.put("name", c.getName());
                            cmap.put("phone", c.getPhone());
                            cmap.put("isPrimary", isPrimary);
                            customersList.add(cmap);
                        }
                    }
                }
            } catch (Exception ex) {
                log.warn("Failed to populate customer details for print job order: {}", order.getId(), ex);
            }
        } else {
            // Already has transient customerName/Phone, but maybe not customers list
            if (order.getCustomers() != null && !order.getCustomers().isEmpty()) {
                for (OrderCustomerDto c : order.getCustomers()) {
                    Map<String, Object> cmap = new LinkedHashMap<>();
                    cmap.put("id", c.getId());
                    cmap.put("name", c.getName());
                    cmap.put("phone", c.getPhone());
                    cmap.put("isPrimary", c.isPrimary());
                    customersList.add(cmap);
                }
            } else if (order.getCustomerId() != null) {
                Map<String, Object> cmap = new LinkedHashMap<>();
                cmap.put("id", order.getCustomerId());
                cmap.put("name", name);
                cmap.put("phone", phone);
                cmap.put("isPrimary", true);
                customersList.add(cmap);
            }
        }

        out.put("customerName", name);
        out.put("customer_name", name);
        out.put("customerPhone", phone);
        out.put("customer_phone", phone);
        if (!customersList.isEmpty()) {
            out.put("customers", customersList);
        }
    }
}
