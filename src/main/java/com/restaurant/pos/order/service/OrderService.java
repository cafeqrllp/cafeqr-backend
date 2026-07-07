package com.restaurant.pos.order.service;

import com.restaurant.pos.accounting.domain.PaymentAllocation;
import com.restaurant.pos.accounting.repository.PaymentAllocationRepository;
import com.restaurant.pos.order.dto.OrderPaymentDto;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.restaurant.pos.delivery.controller.OrderStatusSseController;
import com.restaurant.pos.accounting.service.AccountingPostingService;
import com.restaurant.pos.common.dto.ConfigurationDto;
import com.restaurant.pos.common.exception.ResourceNotFoundException;
import com.restaurant.pos.common.exception.BusinessException;
import com.restaurant.pos.common.service.BranchContextService;
import com.restaurant.pos.common.service.SystemConfigurationService;
import com.restaurant.pos.common.tenant.TenantContext;
import com.restaurant.pos.common.util.SecurityUtils;
import com.restaurant.pos.credit.domain.CreditCustomer;
import com.restaurant.pos.credit.repository.CreditCustomerRepository;
import com.restaurant.pos.inventory.service.InventoryService;
import com.restaurant.pos.invoice.domain.Invoice;
import com.restaurant.pos.invoice.domain.InvoiceType;
import com.restaurant.pos.invoice.domain.InvoiceLine;
import com.restaurant.pos.order.domain.Order;
import com.restaurant.pos.order.domain.OrderLine;
import com.restaurant.pos.order.domain.OrderType;
import com.restaurant.pos.order.domain.OrderStatus;
import com.restaurant.pos.order.domain.PaymentStatus;
import com.restaurant.pos.order.domain.Payment;
import com.restaurant.pos.order.domain.PaymentSplit;
import com.restaurant.pos.order.domain.PaymentType;
import com.restaurant.pos.order.domain.DiscountEngineVersion;
import com.restaurant.pos.order.dto.OrderCancelRequest;
import com.restaurant.pos.order.dto.OrderCreditCompletionRequest;
import com.restaurant.pos.order.dto.OrderCustomerDto;
import com.restaurant.pos.order.dto.CalculationRequest;
import com.restaurant.pos.order.dto.CalculationLineRequest;
import com.restaurant.pos.order.dto.CalculatedLine;
import com.restaurant.pos.order.dto.CalculationResult;
import com.restaurant.pos.order.dto.OrderLineSummaryDto;
import com.restaurant.pos.order.dto.OrderMoveTableRequest;
import com.restaurant.pos.order.dto.OrderSettleRequest;
import com.restaurant.pos.order.dto.OrderSummaryDto;
import com.restaurant.pos.invoice.repository.InvoiceRepository;
import com.restaurant.pos.order.repository.OrderRepository;
import com.restaurant.pos.order.repository.PaymentRepository;
import com.restaurant.pos.order.repository.PaymentSplitRepository;
import com.restaurant.pos.print.domain.PrintJobKind;
import com.restaurant.pos.print.service.PrintJobService;
import com.restaurant.pos.push.service.PushNotificationService;
import com.restaurant.pos.product.domain.Product;
import com.restaurant.pos.product.repository.ProductRepository;
import com.restaurant.pos.common.context.TimezoneResolver;
import com.restaurant.pos.sequence.domain.DocumentType;
import com.restaurant.pos.sequence.service.DocumentSequenceService;
import com.restaurant.pos.sequence.service.OfflineSequenceLeaseService;
import com.restaurant.pos.table.domain.RestaurantTable;
import com.restaurant.pos.table.repository.RestaurantTableRepository;
import com.restaurant.pos.purchasing.domain.Customer;
import com.restaurant.pos.purchasing.repository.CustomerRepository;
import com.restaurant.pos.purchasing.repository.CurrencyRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class OrderService {
    private static final List<String> CLOSED_SALE_STATUSES = List.of("COMPLETED", "PAID", "CANCELLED", "VOID");
    private static final int DEFAULT_HISTORY_PAGE_SIZE = 20;
    private static final int MAX_HISTORY_PAGE_SIZE = 5000;
    private static final int MAX_SYNC_ORDER_CHANGES = 200;
    private static final Duration DEFAULT_HISTORY_WINDOW = Duration.ofDays(1);
    private static final Duration MAX_HISTORY_WINDOW = Duration.ofDays(31);
    private static final List<String> PAYMENT_METHODS = List.of("CASH", "ONLINE", "UPI", "CARD", "BANK", "CHEQUE",
            "MIXED");
    private static final List<String> PAYMENT_SPLIT_METHODS = List.of("CASH", "ONLINE", "UPI", "CARD", "BANK",
            "CHEQUE");

    private static final Map<String, Set<String>> ALLOWED_OPERATIONAL_TRANSITIONS = Map.of(
            "DRAFT", Set.of("CONFIRMED", "CANCELLED"),
            "CONFIRMED", Set.of("IN_PROGRESS", "READY", "CANCELLED"),
            "IN_PROGRESS", Set.of("READY", "CONFIRMED", "CANCELLED"),
            "READY", Set.of("CONFIRMED", "IN_PROGRESS", "CANCELLED"),
            "PENDING", Set.of("CONFIRMED", "CANCELLED")
    );

    private static final Set<String> FINANCIAL_STATUS_NAMES = Set.of(
            "BILLED", "COMPLETED", "VOID"
    );

    private final OrderRepository orderRepository;
    private final InvoiceRepository invoiceRepository;
    private final PaymentRepository paymentRepository;
    private final PaymentSplitRepository paymentSplitRepository;
    private final PaymentAllocationRepository paymentAllocationRepository;
    private final AccountingPostingService accountingPostingService;
    private final InventoryService inventoryService;
    private final RestaurantTableRepository tableRepository;
    private final DocumentSequenceService sequenceService;
    private final OfflineSequenceLeaseService offlineSequenceLeaseService;
    private final PrintJobService printJobService;
    private final ProductRepository productRepository;
    private final CustomerRepository customerRepository;
    private final CreditCustomerRepository creditCustomerRepository;
    private final SystemConfigurationService configurationService;
    private final ObjectMapper objectMapper;
    private final BranchContextService branchContext;
    private final com.restaurant.pos.auth.repository.UserRepository userRepository;
    private final PushNotificationService pushNotificationService;
    private final TimezoneResolver timezoneResolver;
    private final CurrencyRepository currencyRepository;
    private final OrderCalculationService orderCalculationService;
    private final org.springframework.context.ApplicationContext applicationContext;

    private void recalculateOrderTotals(Order order) {
        if (order == null) return;
        
        boolean hasValidLines = false;
        if (order.getLines() != null) {
            hasValidLines = order.getLines().stream()
                    .filter(line -> "Y".equalsIgnoreCase(line.getIsactive()))
                    .anyMatch(line -> line.getUnitPrice() != null || line.getProductId() != null);
        }
        if (!hasValidLines) {
            BigDecimal currentTotal = BigDecimal.ZERO;
            if (order.getLines() != null) {
                currentTotal = order.getLines().stream()
                        .filter(OrderLine::isActive)
                        .map(l -> l.getLineTotal() != null ? l.getLineTotal() : BigDecimal.ZERO)
                        .reduce(BigDecimal.ZERO, BigDecimal::add);
            }
            if (currentTotal.compareTo(BigDecimal.ZERO) <= 0) {
                currentTotal = order.getTotalAmount() != null && order.getTotalAmount().compareTo(BigDecimal.ZERO) > 0
                        ? order.getTotalAmount()
                        : (order.getGrandTotal() != null ? order.getGrandTotal() : BigDecimal.ZERO);
            }

            BigDecimal disc = BigDecimal.ZERO;
            if (order.getOrderDiscountValue() != null) {
                if ("PERCENT".equalsIgnoreCase(order.getOrderDiscountType())) {
                    disc = currentTotal.multiply(order.getOrderDiscountValue().divide(BigDecimal.valueOf(100), 8, RoundingMode.HALF_UP));
                } else {
                    disc = order.getOrderDiscountValue();
                }
            }
            order.setTotalDiscountAmount(disc.setScale(2, RoundingMode.HALF_UP));

            if (order.getDiscountSource() == com.restaurant.pos.order.domain.DiscountSource.MANUAL) {
                BigDecimal roundOff = order.getRoundOffAmount() != null ? order.getRoundOffAmount() : BigDecimal.ZERO;
                BigDecimal finalPayable = currentTotal.subtract(disc).add(roundOff).max(BigDecimal.ZERO);
                order.setGrandTotal(finalPayable.setScale(2, RoundingMode.HALF_UP));
            }
            return;
        }
        
        ConfigurationDto config = configurationService.getEffectiveConfigurationForBranch(order.getOrgId());
        
        List<CalculationLineRequest> lineRequests = new ArrayList<>();
        if (order.getLines() != null) {
            for (OrderLine line : order.getLines()) {
                if (!"Y".equalsIgnoreCase(line.getIsactive())) continue;
                CalculationLineRequest req = CalculationLineRequest.builder()
                        .lineId(line.getId())
                        .clientLineId(line.getClientLineId())
                        .productId(line.getProductId())
                        .variantId(line.getVariantId())
                        .productName(line.getProductName())
                        .categoryName(line.getCategoryName())
                        .isPackagedGood(line.getIsPackagedGood())
                        .quantity(line.getQuantity())
                        .unitPrice(line.getUnitPrice())
                        .taxRate(line.getTaxRate())
                        .taxType(line.getTaxType() != null ? line.getTaxType().name() : null)
                        .taxCode(line.getTaxCode())
                        .taxName(line.getTaxName())
                        .lineDiscountType(resolveLineDiscountType(line))
                        .lineDiscountValue(resolveLineDiscountValue(line))
                        .discountAmount(line.getManualDiscountAmount())
                        .discountPercent(line.getManualDiscountPercent())
                        .build();
                lineRequests.add(req);
            }
        }
        
        CalculationRequest request = CalculationRequest.builder()
                .lines(lineRequests)
                .orderDiscountType(order.getOrderDiscountType())
                .orderDiscountValue(order.getOrderDiscountValue())
                .requestedRoundOff(order.getRoundOffAmount())
                .roundOffMode(order.getRoundOffMode())
                .orgId(order.getOrgId())
                .build();
                
        CalculationResult result = orderCalculationService.calculate(request, config);
        
        order.setGrossAmount(result.getGrossAmount());
        order.setTotalDiscountAmount(result.getLineDiscountDisplayAmount().add(result.getOrderDiscountDisplayAmount()));
        order.setTotalTaxAmount(result.getTotalTax());
        order.setTotalAmount(result.getTotalBeforeRoundOff());
        order.setGrandTotal(result.getGrandTotal());
        order.setRoundOffAmount(result.getRoundOffAmount());
        
        // Map transient fields for discount breakdown face/base
        order.setLineDiscountFaceAmount(result.getLineDiscountDisplayAmount());
        order.setLineDiscountBaseAmount(result.getTotalLineDiscountBase());
        order.setOrderDiscountFaceAmount(result.getOrderDiscountDisplayAmount());
        order.setOrderDiscountBaseAmount(result.getTotalOrderDiscountBase());

        // Stamp the authoritative calculation version from result so Order and Invoice are always in sync
        order.setDiscountCalculationVersion(result.getEngineVersion());
        
        java.util.Map<UUID, CalculatedLine> byLineId = result.getLines().stream()
                .filter(cl -> cl.getLineId() != null)
                .collect(Collectors.toMap(CalculatedLine::getLineId, cl -> cl));
        java.util.Map<UUID, CalculatedLine> byClientLineId = result.getLines().stream()
                .filter(cl -> cl.getClientLineId() != null)
                .collect(Collectors.toMap(CalculatedLine::getClientLineId, cl -> cl));

        if (order.getLines() != null) {
            for (OrderLine line : order.getLines()) {
                if (!"Y".equalsIgnoreCase(line.getIsactive())) continue;

                CalculatedLine cl = null;
                // Prefer stable clientLineId, fall back to persisted lineId — never positional index
                if (line.getClientLineId() != null) {
                    cl = byClientLineId.get(line.getClientLineId());
                }
                if (cl == null && line.getId() != null) {
                    cl = byLineId.get(line.getId());
                }
                if (cl == null) {
                    throw new IllegalStateException(
                        "Financial integrity error: calculation result missing for active order line" +
                        " [lineId=" + line.getId() + ", clientLineId=" + line.getClientLineId() + "]." +
                        " This indicates a line identity mismatch. Aborting save.");
                }

                line.setGrossLineAmount(cl.getGrossFaceAmount());
                line.setUnitPriceExTax(cl.getUnitPriceExTax());
                line.setTaxableAmount(cl.getTaxableAmount());
                line.setTaxType(cl.getTaxType());
                line.setTaxRate(cl.getTaxRate());
                line.setTaxSnapshotRate(cl.getTaxRate());
                line.setTaxAmount(cl.getTaxAmount());
                line.setLineTotal(cl.getLineTotal());

                if ("PERCENT".equalsIgnoreCase(cl.getLineDiscountInputType())) {
                    line.setManualDiscountPercent(cl.getLineDiscountInputValue());
                    line.setManualDiscountAmount(null);
                } else if ("AMOUNT".equalsIgnoreCase(cl.getLineDiscountInputType())) {
                    line.setManualDiscountAmount(cl.getLineDiscountInputValue());
                    line.setManualDiscountPercent(null);
                } else {
                    line.setManualDiscountAmount(null);
                    line.setManualDiscountPercent(null);
                }
                line.setDiscountAmount(cl.getLineDiscountFaceAmount().add(cl.getAllocatedOrderDiscountFace()));
                line.setAllocatedOrderDiscount(cl.getAllocatedOrderDiscountBase());
            }
        }
    }

    private String resolveLineDiscountType(OrderLine line) {
        if (line.getManualDiscountPercent() != null
                && line.getManualDiscountPercent().compareTo(BigDecimal.ZERO) > 0) {
            return "PERCENT";
        }
        if (line.getManualDiscountAmount() != null
                && line.getManualDiscountAmount().compareTo(BigDecimal.ZERO) > 0) {
            return "AMOUNT";
        }
        return null;
    }

    private BigDecimal resolveLineDiscountValue(OrderLine line) {
        if (line.getManualDiscountPercent() != null
                && line.getManualDiscountPercent().compareTo(BigDecimal.ZERO) > 0) {
            return line.getManualDiscountPercent();
        }
        if (line.getManualDiscountAmount() != null
                && line.getManualDiscountAmount().compareTo(BigDecimal.ZERO) > 0) {
            return line.getManualDiscountAmount();
        }
        return null;
    }

    private void prepareSourceFields(Order order) {
        UUID terminalId = TenantContext.getCurrentTerminal();
        if (order.getTerminalId() == null) {
            order.setTerminalId(terminalId);
        }
        if (order.getSourceTerminalId() == null) {
            order.setSourceTerminalId(order.getTerminalId() != null ? order.getTerminalId() : terminalId);
        }
        if (order.getSyncOrigin() == null || order.getSyncOrigin().isBlank()) {
            order.setSyncOrigin(order.getSourceOperationId() == null ? "CLOUD_ONLINE" : "OFFLINE_QUEUE");
        }
        if (order.getCurrencyId() == null) {
            UUID orgId = order.getOrgId();
            if (orgId == null) {
                orgId = resolveOrderWriteOrgId(order);
            }
            UUID clientId = order.getClientId() != null ? order.getClientId() : TenantContext.getCurrentTenant();
            if (clientId != null) {
                if (orgId != null) {
                    currencyRepository.findByClientIdAndOrgIdAndIsDefaultTrue(clientId, orgId)
                        .stream().findFirst()
                        .ifPresentOrElse(
                            c -> order.setCurrencyId(c.getId()),
                            () -> currencyRepository.findByClientIdAndIsDefaultTrue(clientId)
                                .stream().findFirst().ifPresent(c -> order.setCurrencyId(c.getId()))
                        );
                } else {
                    currencyRepository.findByClientIdAndIsDefaultTrue(clientId)
                        .stream().findFirst().ifPresent(c -> order.setCurrencyId(c.getId()));
                }
            }
        }
    }

    private void assignOrderNumber(Order order) {
        OrderType type = order.getOrderType() == null ? OrderType.SALE : order.getOrderType();
        DocumentType docType = switch (type) {
            case PURCHASE -> DocumentType.PURCHASE_ORDER;
            case EXPENSE -> DocumentType.EXPENSE;
            default -> DocumentType.SALE_ORDER;
        };
        order.setOrderNo(resolveDocumentNumber(order, docType, order.getOrderNo()));
    }

    private String resolveDocumentNumber(Order order, DocumentType documentType, String requestedNumber) {
        if (requestedNumber != null && !requestedNumber.isBlank()) {
            if (isMainOfflineSync(order) || (order != null && (order.getOriginalOrderId() != null || (order.getRevisionNumber() != null && order.getRevisionNumber() > 0)))) {
                if (isMainOfflineSync(order)) {
                    offlineSequenceLeaseService.consumeLeasedNumber(
                            documentType,
                            requestedNumber,
                            order.getSourceTerminalId() != null ? order.getSourceTerminalId() : order.getTerminalId());
                }
                return requestedNumber;
            }
        }
        UUID orgId = order != null ? order.getOrgId() : null;
        if (orgId == null) {
            orgId = resolveOrderWriteOrgId(order);
        }
        return sequenceService.generateNextSequence(documentType, orgId);
    }

    private boolean isMainOfflineSync(Order order) {
        return order != null && ("MAIN_OFFLINE".equalsIgnoreCase(order.getSyncOrigin()) 
                || "OFFLINE_QUEUE".equalsIgnoreCase(order.getSyncOrigin()));
    }

    private UUID resolveOrderWriteOrgId(Order order) {
        UUID currentOrgId = TenantContext.getCurrentOrg();
        UUID requestedOrgId = order != null ? order.getOrgId() : null;
        if (currentOrgId != null) {
            if (requestedOrgId != null && !currentOrgId.equals(requestedOrgId)) {
                throw new BusinessException("Selected branch does not match the requested order branch.");
            }
            return currentOrgId;
        }

        if (order != null && order.getTableId() != null) {
            RestaurantTable table = tableRepository
                    .findByIdAndClientId(order.getTableId(), TenantContext.getCurrentTenant())
                    .orElseThrow(() -> new ResourceNotFoundException("Target table not found"));
            if (table.getOrgId() != null) {
                if (requestedOrgId != null && !requestedOrgId.equals(table.getOrgId())) {
                    throw new BusinessException("Selected table belongs to another branch.");
                }
                return table.getOrgId();
            }
        }

        return branchContext.requireWriteOrgId(requestedOrgId);
    }

    private LocalDateTime sourceBusinessDateTime(Order order) {
        if (order != null && order.getOrderDate() != null) {
            ZoneId zoneId = timezoneResolver.resolveTimezone(order.getClientId(), order.getOrgId());
            return LocalDateTime.ofInstant(order.getOrderDate(), zoneId);
        }
        if (order != null && order.getOfflineCreatedAt() != null) {
            return order.getOfflineCreatedAt();
        }
        return LocalDateTime.now();
    }

    private boolean shouldGenerateInvoice(Order order) {
        if (order == null) {
            return false;
        }
        String status = order.getOrderStatus();
        if (order.getOrderType() == OrderType.SALE) {
            return "KITCHEN".equalsIgnoreCase(status)
                    || "CONFIRMED".equalsIgnoreCase(status)
                    || "IN_PROGRESS".equalsIgnoreCase(status)
                    || "READY".equalsIgnoreCase(status)
                    || "BILLED".equalsIgnoreCase(status)
                    || "COMPLETED".equalsIgnoreCase(status);
        }
        return "BILLED".equalsIgnoreCase(status) || "COMPLETED".equalsIgnoreCase(status);
    }

    private void enqueueCloudPrintJobs(Order order) {
        enqueueCloudPrintJobs(order, null, null);
    }

    private void enqueueCloudPrintJobs(Order order, List<OrderLine> addedLines, List<OrderLine> removedLines) {
        try {
            if (order == null || order.getOrderType() != OrderType.SALE || isMainOfflineSync(order)) {
                log.info("enqueueCloudPrintJobs skipped: order is null or not a sale, or offline sync. Order: {}", order != null ? order.getId() : "null");
                return;
            }
            String status = order.getOrderStatus();
            log.info("enqueueCloudPrintJobs check: orderId={}, status={}, addedLines={}, removedLines={}", order.getId(), status, (addedLines != null ? addedLines.size() : 0), (removedLines != null ? removedLines.size() : 0));
            if ("KITCHEN".equalsIgnoreCase(status)
                    || "CONFIRMED".equalsIgnoreCase(status)
                    || "IN_PROGRESS".equalsIgnoreCase(status)
                    || "READY".equalsIgnoreCase(status)) {
                if (shouldSkipAutoPrint(order, PrintJobKind.KOT)) {
                    log.info("Skipping backend auto KOT print job for order {} because requester will print locally",
                            order.getId());
                    return;
                }
                if (addedLines != null || removedLines != null) {
                    if (!addedLines.isEmpty() || !removedLines.isEmpty()) {
                        log.info("Calling enqueueKotEditJob for order {}", order.getId());
                        printJobService.enqueueKotEditJob(order, addedLines, removedLines, "edit");
                    } else {
                        log.info("addedLines and removedLines are both empty for order {}, no KOT edit generated", order.getId());
                    }
                } else {
                    log.info("Calling enqueueForOrder (New KOT) for order {}", order.getId());
                    printJobService.enqueueForOrder(order, PrintJobKind.KOT, "auto");
                }
            } else if ("BILLED".equalsIgnoreCase(status)) {
                if (shouldSkipAutoPrint(order, PrintJobKind.BILL)) {
                    log.info(
                            "Skipping backend auto BILL print job for billed order {} because requester will print locally",
                            order.getId());
                    return;
                }
                printJobService.enqueueForOrder(order, PrintJobKind.BILL, "auto");
            } else if ("COMPLETED".equalsIgnoreCase(status)) {
                if (shouldSkipAutoPrint(order, PrintJobKind.BILL)) {
                    log.info(
                            "Skipping backend auto BILL print job for completed order {} because requester will print locally",
                            order.getId());
                    return;
                }
                printJobService.enqueueForOrder(order, PrintJobKind.BILL, "auto");
            }
        } catch (Exception ex) {
            log.warn("Unable to enqueue cloud print job for order {}", order == null ? null : order.getId(), ex);
        }
    }

    private boolean shouldSkipAutoPrint(Order order, PrintJobKind kind) {
        if (order == null || kind == null || order.getSkipAutoPrintKinds() == null) {
            return false;
        }
        return order.getSkipAutoPrintKinds().stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .anyMatch(value -> kind.name().equalsIgnoreCase(value));
    }

    private void calculateKotDelta(Order oldOrder, Order newOrder, List<OrderLine> addedLines,
            List<OrderLine> removedLines) {
        if (oldOrder == null || newOrder == null) {
            return;
        }
        java.util.Map<String, java.math.BigDecimal> oldQtyMap = new java.util.HashMap<>();
        java.util.Map<String, OrderLine> oldLineMap = new java.util.HashMap<>();
        if (oldOrder.getLines() != null) {
            for (OrderLine line : oldOrder.getLines()) {
                if (line.getProductId() == null)
                    continue;
                String key = line.getProductId() + ":" + (line.getVariantId() != null ? line.getVariantId() : "null");
                oldQtyMap.put(key, oldQtyMap.getOrDefault(key, java.math.BigDecimal.ZERO)
                        .add(line.getQuantity() != null ? line.getQuantity() : java.math.BigDecimal.ZERO));
                oldLineMap.put(key, line);
            }
        }

        java.util.Map<String, java.math.BigDecimal> newQtyMap = new java.util.HashMap<>();
        java.util.Map<String, OrderLine> newLineMap = new java.util.HashMap<>();
        if (newOrder.getLines() != null) {
            for (OrderLine line : newOrder.getLines()) {
                if (line.getProductId() == null)
                    continue;
                String key = line.getProductId() + ":" + (line.getVariantId() != null ? line.getVariantId() : "null");
                newQtyMap.put(key, newQtyMap.getOrDefault(key, java.math.BigDecimal.ZERO)
                        .add(line.getQuantity() != null ? line.getQuantity() : java.math.BigDecimal.ZERO));
                newLineMap.put(key, line);
            }
        }

        // 1. Added or increased items
        for (java.util.Map.Entry<String, java.math.BigDecimal> entry : newQtyMap.entrySet()) {
            String key = entry.getKey();
            java.math.BigDecimal newQty = entry.getValue();
            java.math.BigDecimal oldQty = oldQtyMap.getOrDefault(key, java.math.BigDecimal.ZERO);
            if (newQty.compareTo(oldQty) > 0) {
                java.math.BigDecimal diffQty = newQty.subtract(oldQty);
                OrderLine baseLine = newLineMap.get(key);
                OrderLine copy = OrderLine.builder()
                        .productId(baseLine.getProductId())
                        .variantId(baseLine.getVariantId())
                        .productName(baseLine.getProductName())
                        .categoryName(baseLine.getCategoryName())
                        .isPackagedGood(baseLine.getIsPackagedGood())
                        .quantity(diffQty)
                        .unitOfMeasure(baseLine.getUnitOfMeasure())
                        .unitPrice(baseLine.getUnitPrice())
                        .taxRate(baseLine.getTaxRate())
                        .build();
                addedLines.add(copy);
            }
        }

        // 2. Removed or decreased items
        for (java.util.Map.Entry<String, java.math.BigDecimal> entry : oldQtyMap.entrySet()) {
            String key = entry.getKey();
            java.math.BigDecimal oldQty = entry.getValue();
            java.math.BigDecimal newQty = newQtyMap.getOrDefault(key, java.math.BigDecimal.ZERO);
            if (oldQty.compareTo(newQty) > 0) {
                java.math.BigDecimal diffQty = oldQty.subtract(newQty);
                OrderLine baseLine = oldLineMap.get(key);
                OrderLine copy = OrderLine.builder()
                        .productId(baseLine.getProductId())
                        .variantId(baseLine.getVariantId())
                        .productName(baseLine.getProductName())
                        .categoryName(baseLine.getCategoryName())
                        .isPackagedGood(baseLine.getIsPackagedGood())
                        .quantity(diffQty)
                        .unitOfMeasure(baseLine.getUnitOfMeasure())
                        .unitPrice(baseLine.getUnitPrice())
                        .taxRate(baseLine.getTaxRate())
                        .build();
                removedLines.add(copy);
            }
        }
    }

    private void validateTableAvailableForNewOrder(Order order) {
        if (order == null || order.getTableId() == null) {
            return;
        }

        OrderType orderType = order.getOrderType() == null ? OrderType.SALE : order.getOrderType();
        if (orderType != OrderType.SALE) {
            return;
        }

        RestaurantTable table = getTenantTable(order.getTableId());
        if (order.getOrgId() != null && table.getOrgId() != null && !order.getOrgId().equals(table.getOrgId())) {
            throw new BusinessException("Selected table belongs to another branch.");
        }
        ensureTableAvailableForOrder(table);
    }

    private void ensureTableAvailableForOrder(RestaurantTable table) {
        String status = normalizeTableStatus(table.getStatus());
        if (!"AVAILABLE".equals(status)) {
            throw new BusinessException(
                    "Table " + table.getTableNumber()
                            + " is currently " + tableStatusLabel(status)
                            + ". Change it to Available before placing an order.");
        }
    }

    private String normalizeTableStatus(String status) {
        if (status == null || status.isBlank()) {
            return "AVAILABLE";
        }
        return status.trim().toUpperCase();
    }

    private String tableStatusLabel(String status) {
        return "MAINTENANCE".equalsIgnoreCase(status) ? "HOLD" : status;
    }

    private Order hydrateOrderLines(Order order) {
        if (order == null || order.getLines() == null || order.getLines().isEmpty()) {
            return order;
        }

        boolean needsHydration = order.getLines().stream()
                .anyMatch(line -> line.getProductId() != null
                        && (line.getProductName() == null || line.getProductName().isBlank()
                                || line.getCategoryName() == null || line.getCategoryName().isBlank()
                                || line.getIsPackagedGood() == null));

        if (!needsHydration) {
            return order;
        }

        List<UUID> productIds = order.getLines().stream()
                .map(OrderLine::getProductId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();

        if (productIds.isEmpty()) {
            return order;
        }

        Map<UUID, Product> productsById = productRepository.findByIdIn(productIds).stream()
                .collect(Collectors.toMap(Product::getId, product -> product));

        for (OrderLine line : order.getLines()) {
            Product product = productsById.get(line.getProductId());
            if (product == null) {
                continue;
            }

            if (order.getOrderType() == OrderType.PURCHASE) {
                if (product.getRecipeLines() != null && !product.getRecipeLines().isEmpty()) {
                    throw new BusinessException(
                            "Product '" + product.getName() + "' has ingredients and cannot be purchased directly.");
                }
            }

            if (line.getProductName() == null || line.getProductName().isBlank()) {
                line.setProductName(product.getName());
            }
            if (line.getCategoryName() == null || line.getCategoryName().isBlank()) {
                line.setCategoryName(product.getCategory() != null ? product.getCategory().getName() : null);
            }
            if (line.getIsPackagedGood() == null) {
                line.setIsPackagedGood(product.isPackagedGood());
            }
        }

        return order;
    }

    private List<Order> hydrateOrderLines(List<Order> orders) {
        orders.forEach(this::hydrateOrderLines);
        return orders;
    }

    private void prepareCustomerFields(Order order) {
        if (order == null)
            return;
        if (order.getOrderType() != null && order.getOrderType() != OrderType.SALE)
            return;

        UUID clientId = order.getClientId();
        UUID orgId = order.getOrgId();
        List<CustomerSelection> selections = customerSelections(order);

        if (selections.isEmpty()) {
            hydrateOrderCustomers(order);
            return;
        }

        order.setCustomers(resolveOrderCustomers(order, selections, clientId, orgId, false));
    }

    private void linkCustomersToSavedOrder(Order order) {
        if (order == null)
            return;
        if (order.getOrderType() != null && order.getOrderType() != OrderType.SALE)
            return;
        if (order.getId() == null) {
            throw new IllegalStateException("Order id is required before linking customers");
        }

        UUID clientId = order.getClientId();
        UUID orgId = order.getOrgId();
        List<CustomerSelection> selections = resolvedCustomerSelections(order);
        if (selections.isEmpty()) {
            selections = customerSelections(order);
        }

        if (selections.isEmpty()) {
            hydrateOrderCustomers(order);
            return;
        }

        order.setCustomers(resolveOrderCustomers(order, selections, clientId, orgId, true));
    }

    private List<OrderCustomerDto> resolveOrderCustomers(
            Order order,
            List<CustomerSelection> selections,
            UUID clientId,
            UUID orgId,
            boolean linkToOrder) {
        List<OrderCustomerDto> linkedCustomers = new ArrayList<>();
        Instant attachedAt = Instant.now();
        for (int i = 0; i < selections.size(); i++) {
            CustomerSelection selection = selections.get(i);
            Customer customer = resolveCustomer(clientId, orgId, selection);
            boolean primary = i == 0;
            if (linkToOrder) {
                linkCustomerToOrder(customer, order.getId(), primary, attachedAt);
            }
            Customer saved = customerRepository.save(customer);
            linkedCustomers.add(toOrderCustomerDto(saved, primary));
            if (primary) {
                order.setCustomerId(saved.getId());
                order.setCustomerName(saved.getName());
                order.setCustomerPhone(saved.getPhone());
            }
        }
        return linkedCustomers;
    }

    private void prepareCreditCustomer(Order order, boolean completingAsCredit) {
        if (order == null || (order.getOrderType() != null && order.getOrderType() != OrderType.SALE)) {
            return;
        }
        UUID creditCustomerId = order.getCreditCustomerId();
        if (creditCustomerId == null) {
            if (completingAsCredit) {
                throw new BusinessException("Credit customer is required for credit sale");
            }
            order.setIsCredit(false);
            return;
        }

        ensureCreditLedgerEnabled(order.getOrgId());
        CreditCustomer creditCustomer = creditCustomerRepository
                .findByIdAndClientId(creditCustomerId, order.getClientId())
                .filter(customer -> !"N".equalsIgnoreCase(customer.getIsactive()))
                .orElseThrow(() -> new ResourceNotFoundException("Credit customer not found"));
        if (!"ACTIVE".equalsIgnoreCase(creditCustomer.getStatus())) {
            throw new BusinessException("Credit customer is suspended");
        }
        if (creditCustomer.getLinkedCustomerId() == null) {
            throw new BusinessException("Credit customer is not linked to a customer record");
        }

        order.setCreditCustomerId(creditCustomer.getId());
        order.setCustomerId(creditCustomer.getLinkedCustomerId());
        order.setCustomerName(creditCustomer.getName());
        order.setCustomerPhone(creditCustomer.getPhone());
        if (completingAsCredit) {
            order.setIsCredit(true);
        } else if (!Boolean.TRUE.equals(order.getIsCredit())) {
            order.setIsCredit(false);
        }
    }

    private void ensureCreditLedgerEnabled(UUID orgId) {
        ConfigurationDto config = orgId != null
                ? configurationService.getEffectiveConfigurationForBranch(orgId)
                : configurationService.getConfiguration();
        if (config == null || !config.isCreditEnabled()) {
            throw new BusinessException("Credit Ledger is not enabled for this organization");
        }
    }

    private boolean shouldLogCreditOrderCreate(Order order) {
        if (order == null) {
            return false;
        }
        return Boolean.TRUE.equals(order.getIsCredit())
                || order.getCreditCustomerId() != null
                || "CREDIT".equalsIgnoreCase(order.getPaymentMethod())
                || "CREDIT".equalsIgnoreCase(order.getReference());
    }

    private void logCreditOrderCreateSuccess(boolean enabled, Order order) {
        if (!enabled) {
            return;
        }
        log.info(
                "credit_order_create_success orderId={} orderNo={} clientId={} orgId={} tableId={} orderStatus={} paymentStatus={} isCredit={} creditCustomerId={} customerId={} grandTotal={} sourceOperationId={} terminalId={}",
                order != null ? order.getId() : null,
                order != null ? order.getOrderNo() : null,
                order != null ? order.getClientId() : null,
                order != null ? order.getOrgId() : null,
                order != null ? order.getTableId() : null,
                order != null ? order.getOrderStatus() : null,
                order != null ? order.getPaymentStatus() : null,
                order != null ? order.getIsCredit() : null,
                order != null ? order.getCreditCustomerId() : null,
                order != null ? order.getCustomerId() : null,
                order != null ? order.getGrandTotal() : null,
                order != null ? order.getSourceOperationId() : null,
                order != null ? order.getTerminalId() : null);
    }

    private void logCreditOrderCreateFailure(boolean enabled, String phase, Order order, RuntimeException ex) {
        if (!enabled) {
            return;
        }
        Throwable root = rootCause(ex);
        log.error(
                "credit_order_create_failed phase={} exception={} rootException={} rootMessage={} orderId={} orderNo={} clientId={} orgId={} tableId={} orderStatus={} paymentStatus={} isCredit={} creditCustomerId={} customerId={} grandTotal={} sourceOperationId={} terminalId={}",
                phase,
                ex.getClass().getName(),
                root.getClass().getName(),
                safeLogMessage(root.getMessage()),
                order != null ? order.getId() : null,
                order != null ? order.getOrderNo() : null,
                order != null ? order.getClientId() : null,
                order != null ? order.getOrgId() : null,
                order != null ? order.getTableId() : null,
                order != null ? order.getOrderStatus() : null,
                order != null ? order.getPaymentStatus() : null,
                order != null ? order.getIsCredit() : null,
                order != null ? order.getCreditCustomerId() : null,
                order != null ? order.getCustomerId() : null,
                order != null ? order.getGrandTotal() : null,
                order != null ? order.getSourceOperationId() : null,
                order != null ? order.getTerminalId() : null,
                ex);
    }

    private Throwable rootCause(Throwable throwable) {
        Throwable current = throwable;
        while (current != null && current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current != null ? current : throwable;
    }

    private String safeLogMessage(String message) {
        if (message == null) {
            return null;
        }
        return message.replace('\r', ' ').replace('\n', ' ');
    }

    private Customer resolveCustomer(UUID clientId, UUID orgId, CustomerSelection selection) {
        if (selection.id() != null) {
            return customerRepository.findByIdAndClientId(selection.id(), clientId)
                    .orElseThrow(() -> new ResourceNotFoundException("Customer not found"));
        }

        String phone = normalizePhone(selection.phone());
        if (phone != null) {
            Optional<Customer> existing = customerRepository.findFirstByPhoneAndClientIdOrderByCreatedAtAsc(phone,
                    clientId);
            if (existing.isPresent()) {
                Customer customer = existing.get();
                if ((customer.getName() == null || customer.getName().isBlank()) && selection.name() != null) {
                    customer.setName(selection.name());
                }
                return customer;
            }
        }

        Customer newCustomer = Customer.builder()
                .name(selection.name() != null && !selection.name().isBlank() ? selection.name().trim() : "Guest")
                .phone(phone)
                .customerCategory("REGULAR")
                .build();
        newCustomer.setClientId(clientId);
        newCustomer.setOrgId(orgId);
        return newCustomer;
    }

    private void linkCustomerToOrder(Customer customer, UUID orderId, boolean primary, Instant attachedAt) {
        if (orderId == null) {
            throw new IllegalStateException("Order id is required before linking customers");
        }
        if (customer.getOrderLinks() == null) {
            customer.setOrderLinks(new ArrayList<>());
        }
        customer.getOrderLinks().removeIf(link -> Objects.equals(orderId, link.getOrderId()));
        customer.getOrderLinks().add(Customer.OrderLink.builder()
                .orderId(orderId)
                .isPrimary(primary)
                .attachedAt(attachedAt.toString())
                .build());
    }

    private List<CustomerSelection> customerSelections(Order order) {
        Map<String, CustomerSelection> selections = new LinkedHashMap<>();
        JsonNode raw = normalizeCustomerIdsNode(order.getCustomerIds());
        if (raw != null && raw.isArray()) {
            raw.forEach(node -> addCustomerSelection(selections, fromCustomerNode(node)));
        } else if (raw != null) {
            addCustomerSelection(selections, fromCustomerNode(raw));
        }

        if (order.getCustomerId() != null) {
            addCustomerSelection(selections, new CustomerSelection(order.getCustomerId(), null, null));
        }
        if ((order.getCustomerName() != null && !order.getCustomerName().isBlank())
                || (order.getCustomerPhone() != null && !order.getCustomerPhone().isBlank())) {
            addCustomerSelection(selections,
                    new CustomerSelection(null, order.getCustomerName(), order.getCustomerPhone()));
        }
        return selections.values().stream()
                .filter(selection -> selection.id() != null
                        || (selection.name() != null && !selection.name().isBlank())
                        || (selection.phone() != null && !selection.phone().isBlank()))
                .toList();
    }

    private List<CustomerSelection> resolvedCustomerSelections(Order order) {
        if (order.getCustomers() == null || order.getCustomers().isEmpty()) {
            return List.of();
        }
        Map<String, CustomerSelection> selections = new LinkedHashMap<>();
        order.getCustomers().forEach(customer -> addCustomerSelection(
                selections,
                new CustomerSelection(customer.getId(), customer.getName(), customer.getPhone())));
        return selections.values().stream()
                .filter(selection -> selection.id() != null
                        || (selection.name() != null && !selection.name().isBlank())
                        || (selection.phone() != null && !selection.phone().isBlank()))
                .toList();
    }

    private JsonNode normalizeCustomerIdsNode(JsonNode raw) {
        if (raw == null || raw.isNull()) {
            return null;
        }
        if (raw.isTextual()) {
            String text = raw.asText();
            if (text == null || text.isBlank()) {
                return null;
            }
            try {
                return objectMapper.readTree(text);
            } catch (Exception ignored) {
                return null;
            }
        }
        return raw;
    }

    private CustomerSelection fromCustomerNode(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isTextual()) {
            return new CustomerSelection(parseUuid(node.asText()), null, null);
        }
        UUID id = parseUuid(textValue(node, "id"));
        String name = textValue(node, "name");
        String phone = textValue(node, "phone");
        return new CustomerSelection(id, name, phone);
    }

    private void addCustomerSelection(Map<String, CustomerSelection> selections, CustomerSelection selection) {
        if (selection == null) {
            return;
        }
        String phone = normalizePhone(selection.phone());
        String key = selection.id() != null
                ? "id:" + selection.id()
                : phone != null ? "phone:" + phone : "name:" + String.valueOf(selection.name()).trim().toLowerCase();
        selections.putIfAbsent(key, new CustomerSelection(selection.id(), trimToNull(selection.name()), phone));
    }

    private UUID parseUuid(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(value.trim());
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private String textValue(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private String normalizePhone(String phone) {
        if (phone == null) {
            return null;
        }
        String normalized = phone.trim().replaceAll("[\\s()\\-]", "");
        return normalized.isBlank() ? null : normalized;
    }

    private Order hydrateOrder(Order order) {
        return hydrateOrderCustomers(hydrateOrderLines(order));
    }

    private List<Order> hydrateOrders(List<Order> orders) {
        return hydrateOrderCustomers(hydrateOrderLines(orders));
    }

    private Order hydrateOrderCustomers(Order order) {
        if (order == null || order.getClientId() == null || order.getId() == null) {
            return order;
        }
        List<Customer> linked = new ArrayList<>(customerRepository.findByClientIdAndOrderLink(
                order.getClientId(),
                orderNeedle(order.getId(), false),
                orderNeedle(order.getId(), true)));
        if (linked.isEmpty() && order.getCustomerId() != null) {
            customerRepository.findByIdAndClientId(order.getCustomerId(), order.getClientId()).ifPresent(linked::add);
        }
        List<OrderCustomerDto> customers = new ArrayList<>();
        for (int i = 0; i < linked.size(); i++) {
            Customer customer = linked.get(i);
            boolean primary = isPrimaryForOrder(customer, order.getId()) || i == 0;
            customers.add(toOrderCustomerDto(customer, primary));
        }
        customers.sort((a, b) -> Boolean.compare(!a.isPrimary(), !b.isPrimary()));
        order.setCustomers(customers);
        customers.stream().filter(OrderCustomerDto::isPrimary).findFirst().ifPresent(primary -> {
            order.setCustomerId(primary.getId());
            order.setCustomerName(primary.getName());
            order.setCustomerPhone(primary.getPhone());
        });
        if (!customers.isEmpty() && (order.getCustomerName() == null || order.getCustomerName().isBlank())) {
            OrderCustomerDto first = customers.get(0);
            order.setCustomerName(first.getName());
            order.setCustomerPhone(first.getPhone());
        }
        return order;
    }

    private List<Order> hydrateOrderCustomers(List<Order> orders) {
        if (orders == null || orders.isEmpty()) {
            return orders;
        }

        List<UUID> orderIds = orders.stream()
                .map(Order::getId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();

        if (orderIds.isEmpty()) {
            return orders;
        }

        UUID clientId = orders.stream()
                .map(Order::getClientId)
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(null);

        if (clientId == null) {
            return orders;
        }

        // 1. Fetch all customers linked to any of the orderIds in ONE query
        List<Customer> linkedCustomers = customerRepository.findByClientIdAndOrderIds(clientId, orderIds);

        // Map orderId -> List of linked customers
        Map<UUID, List<Customer>> orderIdToCustomers = new java.util.HashMap<>();
        for (Customer customer : linkedCustomers) {
            if (customer.getOrderLinks() != null) {
                for (Customer.OrderLink link : customer.getOrderLinks()) {
                    if (link.getOrderId() != null) {
                        orderIdToCustomers.computeIfAbsent(link.getOrderId(), k -> new ArrayList<>()).add(customer);
                    }
                }
            }
        }

        // 2. Fetch direct customers by customerId in ONE query if not already fetched
        List<UUID> directCustomerIds = orders.stream()
                .map(Order::getCustomerId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();

        Map<UUID, Customer> directCustomerMap = new java.util.HashMap<>();
        if (!directCustomerIds.isEmpty()) {
            List<Customer> directCustomers = customerRepository.findByIdInAndClientId(directCustomerIds, clientId);
            for (Customer c : directCustomers) {
                directCustomerMap.put(c.getId(), c);
            }
        }

        // 3. Hydrate each order
        for (Order order : orders) {
            if (order.getId() == null) {
                continue;
            }
            List<Customer> linked = orderIdToCustomers.getOrDefault(order.getId(), new ArrayList<>());

            // Sort linked customers so the primary one is first
            linked.sort((c1, c2) -> {
                boolean p1 = isPrimaryForOrder(c1, order.getId());
                boolean p2 = isPrimaryForOrder(c2, order.getId());
                return Boolean.compare(!p1, !p2);
            });

            if (linked.isEmpty() && order.getCustomerId() != null) {
                Customer dc = directCustomerMap.get(order.getCustomerId());
                if (dc != null) {
                    linked.add(dc);
                }
            }

            List<OrderCustomerDto> customers = new ArrayList<>();
            for (int i = 0; i < linked.size(); i++) {
                Customer customer = linked.get(i);
                boolean primary = isPrimaryForOrder(customer, order.getId()) || i == 0;
                customers.add(toOrderCustomerDto(customer, primary));
            }
            customers.sort((a, b) -> Boolean.compare(!a.isPrimary(), !b.isPrimary()));
            order.setCustomers(customers);
            customers.stream().filter(OrderCustomerDto::isPrimary).findFirst().ifPresent(primary -> {
                order.setCustomerId(primary.getId());
                order.setCustomerName(primary.getName());
                order.setCustomerPhone(primary.getPhone());
            });
            if (!customers.isEmpty() && (order.getCustomerName() == null || order.getCustomerName().isBlank())) {
                OrderCustomerDto first = customers.get(0);
                order.setCustomerName(first.getName());
                order.setCustomerPhone(first.getPhone());
            }
        }

        return orders;
    }

    private OrderSummaryDto toOrderSummary(Order order) {
        Order hydrated = hydrateOrderCustomers(hydrateOrderLines(order));
        List<OrderCustomerDto> customers = hydrated.getCustomers() == null ? List.of() : hydrated.getCustomers();
        OrderCustomerDto primaryCustomer = customers.stream()
                .filter(OrderCustomerDto::isPrimary)
                .findFirst()
                .orElse(customers.isEmpty() ? null : customers.get(0));

        return OrderSummaryDto.builder()
                .id(hydrated.getId())
                .orderNo(hydrated.getOrderNo())
                .orderType(hydrated.getOrderType())
                .orderStatus(hydrated.getOrderStatus())
                .paymentStatus(hydrated.getPaymentStatus())
                .fulfillmentType(hydrated.getFulfillmentType())
                .tableId(hydrated.getTableId())
                .tableNumber(hydrated.getTableNumber())
                .customerId(primaryCustomer != null ? primaryCustomer.getId() : hydrated.getCustomerId())
                .customerName(primaryCustomer != null ? primaryCustomer.getName() : hydrated.getCustomerName())
                .customerPhone(primaryCustomer != null ? primaryCustomer.getPhone() : hydrated.getCustomerPhone())
                .isCredit(hydrated.getIsCredit())
                .creditCustomerId(hydrated.getCreditCustomerId())
                .customers(customers)
                .totalAmount(hydrated.getTotalAmount())
                .totalTaxAmount(hydrated.getTotalTaxAmount())
                .totalDiscountAmount(hydrated.getTotalDiscountAmount())
                .grandTotal(hydrated.getGrandTotal())
                .grossAmount(hydrated.getGrossAmount())
                .discountCalculationVersion(hydrated.getDiscountCalculationVersion())
                .orderDate(hydrated.getOrderDate())
                .createdAt(hydrated.getCreatedAt())
                .updatedAt(hydrated.getUpdatedAt())
                .createdBy(resolveUserDisplayName(hydrated.getCreatedBy()))
                .updatedBy(resolveUserDisplayName(hydrated.getUpdatedBy()))
                .invoiceNo(hydrated.getInvoiceNo())
                .dailyBillNo(hydrated.getDailyBillNo())
                .paymentNo(hydrated.getPaymentNo())
                .paymentMethod(firstNonBlank(hydrated.getPaymentMethod(), hydrated.getReference()))
                .description(hydrated.getDescription())
                .lines(toOrderLineSummaries(hydrated.getLines()))
                .warehouseId(hydrated.getWarehouseId())
                .vendorId(hydrated.getVendorId())
                .build();
    }

    private List<OrderLineSummaryDto> toOrderLineSummaries(List<OrderLine> lines) {
        if (lines == null || lines.isEmpty()) {
            return List.of();
        }
        return lines.stream()
                .filter(OrderLine::isActive)
                .map(line -> OrderLineSummaryDto.builder()
                        .id(line.getId())
                        .productId(line.getProductId())
                        .variantId(line.getVariantId())
                        .productName(line.getProductName())
                        .categoryName(line.getCategoryName())
                        .isPackagedGood(line.getIsPackagedGood())
                        .quantity(line.getQuantity())
                        .unitOfMeasure(line.getUnitOfMeasure())
                        .uomPrecision(line.getUomPrecision())
                        .unitPrice(line.getUnitPrice())
                        .taxRate(line.getTaxRate())
                        .taxAmount(line.getTaxAmount())
                        .discountAmount(line.getDiscountAmount())
                        .lineTotal(line.getLineTotal())
                        // GST Enrichment Fields (V1_110)
                        .grossLineAmount(line.getGrossLineAmount())
                        .unitPriceExTax(line.getUnitPriceExTax())
                        .taxableAmount(line.getTaxableAmount())
                        .taxType(line.getTaxType())
                        .taxSnapshotRate(line.getTaxSnapshotRate())
                        .taxCode(line.getTaxCode())
                        .taxName(line.getTaxName())
                        .manualDiscountAmount(line.getManualDiscountAmount())
                        .manualDiscountPercent(line.getManualDiscountPercent())
                        .allocatedOrderDiscount(line.getAllocatedOrderDiscount())
                        .build())
                .toList();
    }

    private List<OrderSummaryDto> mergeOrderSummaries(List<OrderSummaryDto> first, List<OrderSummaryDto> second) {
        Map<UUID, OrderSummaryDto> merged = new LinkedHashMap<>();
        first.forEach(order -> {
            if (order.getId() != null)
                merged.put(order.getId(), order);
        });
        second.forEach(order -> {
            if (order.getId() != null)
                merged.putIfAbsent(order.getId(), order);
        });
        return new ArrayList<>(merged.values());
    }

    private int clampPageSize(int requestedSize) {
        int safe = requestedSize > 0 ? requestedSize : DEFAULT_HISTORY_PAGE_SIZE;
        return Math.min(safe, MAX_HISTORY_PAGE_SIZE);
    }

    private void validateHistoryWindow(Instant fromDate, Instant toDate) {
        if (fromDate == null || toDate == null) {
            return;
        }
        if (fromDate.isAfter(toDate)) {
            throw new IllegalArgumentException("fromDate must be before toDate");
        }
        if (Duration.between(fromDate, toDate).compareTo(MAX_HISTORY_WINDOW) > 0) {
            throw new IllegalArgumentException("Order history range cannot exceed 31 days");
        }
    }

    private String normalizeHistorySearch(String searchTerm) {
        if (searchTerm == null) {
            return null;
        }
        String trimmed = searchTerm.trim();
        return trimmed.isBlank() ? null : trimmed;
    }

    private String likePattern(String searchTerm) {
        return "%" + searchTerm.toLowerCase()
                .replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_") + "%";
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private Set<UUID> findCustomerSearchCustomerIds(UUID tenantId, UUID orgId, String searchTerm) {
        if (searchTerm == null) {
            return Set.of();
        }
        return new LinkedHashSet<>(customerRepository.findIdsByClientAndOrgAndSearch(
                tenantId,
                orgId,
                likePattern(searchTerm)));
    }

    private Set<UUID> findCustomerSearchOrderIds(UUID tenantId, UUID orgId, String searchTerm) {
        if (searchTerm == null) {
            return Set.of();
        }
        return new LinkedHashSet<>(customerRepository.findLinkedOrderIdsByClientAndOrgAndCustomerSearch(
                tenantId,
                orgId,
                likePattern(searchTerm)));
    }

    private Specification<Order> salesHistorySpec(
            UUID orgId,
            UUID terminalId,
            Instant fromDate,
            Instant toDate,
            String searchTerm,
            boolean exactDocumentSearch,
            Set<UUID> customerIds,
            Set<UUID> customerOrderIds,
            String status) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            UUID tenantId = TenantContext.getCurrentTenant();

            predicates.add(cb.equal(root.get("clientId"), tenantId));
            if (orgId != null) {
                predicates.add(cb.equal(root.get("orgId"), orgId));
            }
            if (terminalId != null) {
                predicates.add(cb.equal(root.get("terminalId"), terminalId));
            }
            predicates.add(cb.equal(root.get("orderType"), OrderType.SALE));

            if (status != null && !status.isBlank() && !"ALL".equalsIgnoreCase(status)) {
                if ("VOID".equalsIgnoreCase(status)) {
                    predicates.add(cb.or(
                            cb.equal(root.get("isactive"), "N"),
                            cb.equal(root.get("orderStatus"), "VOID")));
                } else if ("PAID".equalsIgnoreCase(status)) {
                    predicates.add(cb.equal(root.get("paymentStatus"), "PAID"));
                    predicates.add(cb.equal(root.get("isactive"), "Y"));
                    predicates.add(cb.notEqual(root.get("orderStatus"), "VOID"));
                    predicates.add(cb.equal(cb.locate(root.get("orderNo"), "_VOID_"), 0));
                } else {
                    predicates.add(cb.equal(root.get("orderStatus"), status));
                    predicates.add(cb.equal(root.get("isactive"), "Y"));
                    predicates.add(cb.notEqual(root.get("orderStatus"), "VOID"));
                    predicates.add(cb.equal(cb.locate(root.get("orderNo"), "_VOID_"), 0));
                }
            } else {
                predicates.add(cb.equal(root.get("isactive"), "Y"));
                predicates.add(cb.notEqual(root.get("orderStatus"), "VOID"));
                predicates.add(cb.equal(cb.locate(root.get("orderNo"), "_VOID_"), 0));
            }

            if (!exactDocumentSearch) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("orderDate"), fromDate));
                predicates.add(cb.lessThanOrEqualTo(root.get("orderDate"), toDate));
            }

            if (searchTerm != null) {
                String lowered = searchTerm.toLowerCase();
                if (exactDocumentSearch) {
                    predicates.add(cb.or(
                            cb.equal(cb.lower(root.get("orderNo")), lowered),
                            cb.equal(cb.lower(root.get("invoiceNo")), lowered),
                            cb.equal(cb.lower(root.get("paymentNo")), lowered)));
                } else {
                    String pattern = likePattern(searchTerm);
                    List<Predicate> searchPredicates = new ArrayList<>();
                    searchPredicates.add(cb.like(cb.lower(root.get("orderNo")), pattern, '\\'));
                    searchPredicates.add(cb.like(cb.lower(root.get("invoiceNo")), pattern, '\\'));
                    searchPredicates.add(cb.like(cb.lower(root.get("paymentNo")), pattern, '\\'));
                    searchPredicates.add(cb.like(cb.lower(root.get("tableNumber")), pattern, '\\'));
                    if (customerIds != null && !customerIds.isEmpty()) {
                        searchPredicates.add(root.get("customerId").in(customerIds));
                    }
                    if (customerOrderIds != null && !customerOrderIds.isEmpty()) {
                        searchPredicates.add(root.get("id").in(customerOrderIds));
                    }
                    predicates.add(cb.or(searchPredicates.toArray(new Predicate[0])));
                }
            }

            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }

    private String orderNeedle(UUID orderId, boolean primary) {
        if (primary) {
            return "[{\"orderId\":\"" + orderId + "\",\"isPrimary\":true}]";
        }
        return "[{\"orderId\":\"" + orderId + "\"}]";
    }

    private boolean isPrimaryForOrder(Customer customer, UUID orderId) {
        if (orderId == null || customer.getOrderLinks() == null) {
            return false;
        }
        return customer.getOrderLinks().stream()
                .anyMatch(
                        link -> Objects.equals(orderId, link.getOrderId()) && Boolean.TRUE.equals(link.getIsPrimary()));
    }

    private OrderCustomerDto toOrderCustomerDto(Customer customer, boolean primary) {
        return OrderCustomerDto.builder()
                .id(customer.getId())
                .name(customer.getName())
                .phone(customer.getPhone())
                .primary(primary)
                .build();
    }

    private record CustomerSelection(UUID id, String name, String phone) {
    }

    public List<Order> getOrders(String status) {
        UUID tenantId = TenantContext.getCurrentTenant();
        UUID orgId = branchContext.getReadOrgId(null);
        List<String> statuses = (status != null && !status.isEmpty()) ? Arrays.asList(status.split(",")) : null;

        List<Order> orders;
        if (orgId == null) {
            if (statuses != null)
                orders = orderRepository.findByClientIdAndOrderStatusInOrderByCreatedAtDesc(tenantId, statuses);
            else
                orders = orderRepository.findByClientIdOrderByCreatedAtDesc(tenantId);
        } else {
            if (statuses != null)
                orders = orderRepository.findByClientIdAndOrgIdAndOrderStatusInOrderByCreatedAtDesc(tenantId, orgId,
                        statuses);
            else
                orders = orderRepository.findByClientIdAndOrgIdOrderByCreatedAtDesc(tenantId, orgId);
        }

        // Lazy generate documents for completed orders that are missing them
        orders.stream()
                .filter(o -> "COMPLETED".equalsIgnoreCase(o.getOrderStatus())
                        && (o.getInvoiceNo() == null || o.getInvoiceNo().isEmpty()))
                .forEach(o -> {
                    try {
                        generateInvoice(o);
                    } catch (Exception ignored) {
                    }
                });

        return hydrateOrders(orders);
    }

    public List<Order> getOrders() {
        return getOrders(null);
    }

    public Page<Order> getOrders(String status, Pageable pageable) {
        UUID tenantId = TenantContext.getCurrentTenant();
        UUID orgId = branchContext.getReadOrgId(null);
        List<String> statuses = (status != null && !status.isEmpty()) ? Arrays.asList(status.split(",")) : null;

        Page<Order> ordersPage;
        if (orgId == null) {
            if (statuses != null) {
                ordersPage = orderRepository.findByClientIdAndOrderStatusIn(tenantId, statuses, pageable);
            } else {
                ordersPage = orderRepository.findByClientId(tenantId, pageable);
            }
        } else {
            if (statuses != null) {
                ordersPage = orderRepository.findByClientIdAndOrgIdAndOrderStatusIn(tenantId, orgId, statuses,
                        pageable);
            } else {
                ordersPage = orderRepository.findByClientIdAndOrgId(tenantId, orgId, pageable);
            }
        }

        // Lazy generate documents for completed orders that are missing them
        ordersPage.getContent().stream()
                .filter(o -> "COMPLETED".equalsIgnoreCase(o.getOrderStatus())
                        && (o.getInvoiceNo() == null || o.getInvoiceNo().isEmpty()))
                .forEach(o -> {
                    try {
                        generateInvoice(o);
                    } catch (Exception ignored) {
                    }
                });

        return ordersPage.map(this::hydrateOrder);
    }

    @Transactional(readOnly = true)
    public org.springframework.data.domain.Page<Order> getOrdersByType(OrderType orderType, org.springframework.data.domain.Pageable pageable) {
        UUID tenantId = TenantContext.getCurrentTenant();
        UUID orgId = branchContext.getReadOrgId(null);
        org.springframework.data.domain.Page<Order> page;
        if (orgId == null) {
            page = orderRepository.findByClientIdAndOrderTypeOrderByCreatedAtDesc(tenantId, orderType, pageable);
        } else {
            page = orderRepository.findByClientIdAndOrgIdAndOrderTypeOrderByCreatedAtDesc(tenantId, orgId, orderType, pageable);
        }
        return page.map(this::hydrateOrder);
    }

    @Transactional(readOnly = true)
    public List<OrderSummaryDto> getLiveSalesOrders() {
        UUID tenantId = TenantContext.getCurrentTenant();
        UUID orgId = branchContext.getReadOrgId(null);
        return orderRepository.findLiveOrders(tenantId, orgId, OrderType.SALE, CLOSED_SALE_STATUSES)
                .stream()
                .map(this::toOrderSummary)
                .toList();
    }

    @Transactional(readOnly = true)
    public Page<OrderSummaryDto> getSalesOrderHistory(Instant fromDate, Instant toDate, int page, int size,
            String searchTerm) {
        return getSalesOrderHistory(fromDate, toDate, page, size, searchTerm, null, null, null);
    }

    @Transactional(readOnly = true)
    public Page<OrderSummaryDto> getSalesOrderHistory(Instant fromDate, Instant toDate, int page, int size,
            String searchTerm, String status) {
        return getSalesOrderHistory(fromDate, toDate, page, size, searchTerm, status, null, null);
    }

    @Transactional(readOnly = true)
    public Page<OrderSummaryDto> getSalesOrderHistory(Instant fromDate, Instant toDate, int page, int size,
            String searchTerm, String status, UUID paramOrgId, UUID terminalId) {
        Instant effectiveTo = toDate != null ? toDate : Instant.now();
        Instant effectiveFrom = fromDate != null ? fromDate : effectiveTo.minus(DEFAULT_HISTORY_WINDOW);
        validateHistoryWindow(effectiveFrom, effectiveTo);
        String normalizedSearch = normalizeHistorySearch(searchTerm);

        Pageable pageable = PageRequest.of(
                Math.max(page, 0),
                clampPageSize(size),
                Sort.by(Sort.Order.desc("orderDate"), Sort.Order.desc("createdAt")));

        UUID orgId;
        if (SecurityUtils.isSuperAdmin()) {
            orgId = paramOrgId != null ? paramOrgId : branchContext.getReadOrgId(null);
        } else {
            orgId = TenantContext.getCurrentOrg();
        }
        if (normalizedSearch != null) {
            Page<Order> exactDocumentMatches = orderRepository.findAll(
                    salesHistorySpec(orgId, terminalId, null, null, normalizedSearch, true, Set.of(), Set.of(), status),
                    pageable);
            if (exactDocumentMatches.hasContent()) {
                return exactDocumentMatches.map(this::toOrderSummary);
            }
        }

        UUID tenantId = TenantContext.getCurrentTenant();
        Set<UUID> customerIds = normalizedSearch == null
                ? Set.of()
                : findCustomerSearchCustomerIds(tenantId, orgId, normalizedSearch);
        Set<UUID> customerOrderIds = normalizedSearch == null
                ? Set.of()
                : findCustomerSearchOrderIds(tenantId, orgId, normalizedSearch);

        return orderRepository.findAll(
                salesHistorySpec(orgId, terminalId, effectiveFrom, effectiveTo, normalizedSearch, false, customerIds,
                        customerOrderIds, status),
                pageable).map(this::toOrderSummary);
    }


    @Transactional(readOnly = true)
    public List<OrderSummaryDto> getSyncBootstrapOrders() {
        List<OrderSummaryDto> live = getLiveSalesOrders();
        Page<OrderSummaryDto> recent = getSalesOrderHistory(
                Instant.now().minus(DEFAULT_HISTORY_WINDOW),
                Instant.now(),
                0,
                MAX_HISTORY_PAGE_SIZE,
                null);
        return mergeOrderSummaries(live, recent.getContent());
    }

    @Transactional(readOnly = true)
    public List<OrderSummaryDto> getChangedSalesOrders(Instant since) {
        Instant safeSince = since != null ? since : Instant.now().minus(Duration.ofMinutes(15));
        LocalDateTime updatedAfter = LocalDateTime.ofInstant(safeSince, ZoneOffset.UTC);
        UUID tenantId = TenantContext.getCurrentTenant();
        UUID orgId = branchContext.getReadOrgId(null);
        return orderRepository.findChangedOrders(
                tenantId,
                orgId,
                OrderType.SALE,
                updatedAfter,
                PageRequest.of(0, MAX_SYNC_ORDER_CHANGES))
                .stream()
                .map(this::toOrderSummary)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<Order> searchOrders(com.restaurant.pos.order.dto.OrderSearchCriteria criteria) {
        UUID clientId = TenantContext.getCurrentTenant();
        UUID orgId = branchContext.getReadOrgId(null);

        org.springframework.data.jpa.domain.Specification<Order> spec = com.restaurant.pos.order.spec.OrderSpecification
                .filterBy(criteria, clientId, orgId);

        return hydrateOrders(orderRepository.findAll(spec, org.springframework.data.domain.Sort
                .by(org.springframework.data.domain.Sort.Direction.DESC, "createdAt")));
    }

    @Transactional(readOnly = true)
    public Page<Order> searchOrders(com.restaurant.pos.order.dto.OrderSearchCriteria criteria, Pageable pageable) {
        UUID clientId = TenantContext.getCurrentTenant();
        UUID orgId = branchContext.getReadOrgId(null);

        org.springframework.data.jpa.domain.Specification<Order> spec = com.restaurant.pos.order.spec.OrderSpecification
                .filterBy(criteria, clientId, orgId);

        return orderRepository.findAll(spec, pageable).map(this::hydrateOrder);
    }

    @Transactional(readOnly = true)
    public Order getOrder(UUID id) {
        UUID tenantId = TenantContext.getCurrentTenant();
        UUID orgId = branchContext.getReadOrgId(null);
        Order order;
        if (orgId == null) {
            order = hydrateOrder(orderRepository.findByIdAndClientId(id, tenantId)
                    .orElseThrow(() -> new ResourceNotFoundException("Order not found or access denied")));
        } else {
            order = hydrateOrder(orderRepository.findByIdAndClientIdAndOrgId(id, tenantId, orgId)
                    .orElseThrow(() -> new ResourceNotFoundException("Order not found or access denied")));
        }

        if ("VOID".equalsIgnoreCase(order.getOrderStatus()) || "N".equalsIgnoreCase(order.getIsactive())) {
            String baseOrderNo = order.getOrderNo();
            if (baseOrderNo != null && baseOrderNo.contains("_VOID_")) {
                baseOrderNo = baseOrderNo.substring(0, baseOrderNo.indexOf("_VOID_"));
            }
            Optional<Order> activeOrder = orgId == null
                    ? orderRepository.findActiveByOrderNoAndClientId(baseOrderNo, tenantId)
                    : orderRepository.findActiveByOrderNoAndClientIdAndOrgId(baseOrderNo, tenantId, orgId);
            if (activeOrder.isPresent()) {
                return hydrateOrder(activeOrder.get());
            }
        }
        return order;
    }

    @Transactional
    public Order createOrder(Order order) {
        log.info("Creating order: {} | Tenant: {} | Org: {}", order, TenantContext.getCurrentTenant(),
                TenantContext.getCurrentOrg());
        String requestedInvoiceNo = order.getOfflineInvoiceNo();
        String requestedPaymentNo = order.getOfflinePaymentNo();

        boolean logCreditDiagnostics = shouldLogCreditOrderCreate(order);
        String diagnosticPhase = "resolve_context";
        try {
            order.setClientId(TenantContext.getCurrentTenant());
            order.setOrgId(resolveOrderWriteOrgId(order));
            prepareSourceFields(order);

            if (order.getOrderStatus() == null) {
                order.setOrderStatus("DRAFT");
            }

            diagnosticPhase = "validate_table_available";
            validateTableAvailableForNewOrder(order);

            diagnosticPhase = "assign_order_number";
            assignOrderNumber(order);

            diagnosticPhase = "prepare_credit_customer";
            prepareCreditCustomer(order, Boolean.TRUE.equals(order.getIsCredit()));

            diagnosticPhase = "prepare_order_lines";
            // Ensure bidirectional mapping
            if (order.getLines() != null) {
                order.getLines().forEach(line -> line.setOrder(order));
            }

            diagnosticPhase = "hydrate_order_inputs";
            hydrateOrderLines(order);
            recalculateOrderTotals(order);
            prepareCustomerFields(order);

            diagnosticPhase = "validate_gst_fields";
            validateGstFields(order);

            diagnosticPhase = "save_order";
            Order saved = orderRepository.save(order);

            diagnosticPhase = "link_customers";
            linkCustomersToSavedOrder(saved);

            // Copy transient payment fields from request order object to saved entity
            saved.setRoundOffAmount(order.getRoundOffAmount());
            saved.setAmountPaid(order.getAmountPaid());
            saved.setPaymentSplits(order.getPaymentSplits());

            diagnosticPhase = "generate_invoice";
            if (shouldGenerateInvoice(saved)) {
                generateInvoice(saved, null, requestedInvoiceNo);
            }

            diagnosticPhase = "post_financials";
            // Resolve payment method safely
            String paymentMethod = order.getPaymentMethod();
            if (paymentMethod == null || paymentMethod.isBlank()) {
                paymentMethod = saved.getPaymentMethod();
            }
            if (paymentMethod == null || paymentMethod.isBlank()) {
                paymentMethod = "CASH";
            }
            boolean isCredit = Boolean.TRUE.equals(saved.getIsCredit()) || "CREDIT".equalsIgnoreCase(paymentMethod);

            if (saved.getOrderType() == OrderType.PURCHASE) {
                if (!"DRAFT".equalsIgnoreCase(saved.getOrderStatus()) && !isCredit) {
                    generatePayment(saved, paymentMethod, requestedPaymentNo);
                }
                if ("COMPLETED".equalsIgnoreCase(saved.getOrderStatus())) {
                    processInventoryForOrder(saved);
                }
            } else {
                // Sales
                if ("COMPLETED".equalsIgnoreCase(saved.getOrderStatus())
                        && "PAID".equalsIgnoreCase(saved.getPaymentStatus())) {
                    String salePaymentMethod = saved.getReference() != null ? saved.getReference() : "CASH";
                    // Convert transient CreateOrderRequest.PaymentSplitRequest to
                    // OrderSettleRequest.PaymentSplitRequest
                    List<OrderSettleRequest.PaymentSplitRequest> settlePaymentSplits = null;
                    if (order.getPaymentSplits() != null && !order.getPaymentSplits().isEmpty()) {
                        settlePaymentSplits = order.getPaymentSplits().stream()
                                .filter(java.util.Objects::nonNull)
                                .map(s -> {
                                    OrderSettleRequest.PaymentSplitRequest sp = new OrderSettleRequest.PaymentSplitRequest();
                                    sp.setPaymentMethod(s.getPaymentMethod());
                                    sp.setAmount(s.getAmount());
                                    sp.setReferenceNo(s.getReferenceNo());
                                    return sp;
                                })
                                .collect(Collectors.toList());
                    }
                    BigDecimal effectiveAmountPaid = order.getAmountPaid() != null ? order.getAmountPaid()
                            : saved.getGrandTotal();
                    BigDecimal effectiveRoundOff = order.getRoundOffAmount() != null ? order.getRoundOffAmount()
                            : BigDecimal.ZERO;
                    generatePayment(saved, salePaymentMethod, requestedPaymentNo, effectiveAmountPaid, null,
                            null, null, settlePaymentSplits, effectiveRoundOff);
                    accountingPostingService.postSaleCogs(saved);
                } else if (isCompletedCreditSale(saved)) {
                    accountingPostingService.postSaleCogs(saved);
                }
            }

            diagnosticPhase = "handle_table_status";
            handleTableStatus(saved);

            diagnosticPhase = "hydrate_saved_order";
            Order hydrated = hydrateOrder(saved);
            hydrated.setSkipAutoPrintKinds(order.getSkipAutoPrintKinds());

            diagnosticPhase = "enqueue_cloud_print_jobs";
            enqueueCloudPrintJobs(hydrated);
            logCreditOrderCreateSuccess(logCreditDiagnostics, hydrated);

            try {
                if (hydrated.getOrderType() == OrderType.SALE) {
                    pushNotificationService.sendNewOrderPush(hydrated);
                }
            } catch (Exception ex) {
                log.error("Failed to send push notification for order {}", hydrated.getId(), ex);
            }

            return hydrated;
        } catch (RuntimeException ex) {
            logCreditOrderCreateFailure(logCreditDiagnostics, diagnosticPhase, order, ex);
            throw ex;
        }
    }

    @Transactional(readOnly = true)
    public Optional<Order> findBySourceOperationId(String sourceOperationId) {
        if (sourceOperationId == null || sourceOperationId.isBlank()) {
            return Optional.empty();
        }
        return orderRepository.findBySourceOperationIdAndClientId(sourceOperationId, TenantContext.getCurrentTenant())
                .map(this::hydrateOrder);
    }

    public com.restaurant.pos.order.dto.IdempotentCreateResult createOrderIdempotently(Order order) {
        if (order.getSourceLocalRef() != null && !order.getSourceLocalRef().isBlank()) {
            UUID clientId = order.getClientId() != null ? order.getClientId() : TenantContext.getCurrentTenant();
            order.setClientId(clientId);
            UUID resolvedOrgId = resolveOrderWriteOrgId(order);
            order.setOrgId(resolvedOrgId);

            Optional<Order> existing = orderRepository.findByClientIdAndOrgIdAndSourceLocalRefAndOrderStatusNot(
                    clientId, resolvedOrgId, order.getSourceLocalRef(), "VOID");
            
            if (existing.isPresent()) {
                Order existingOrder = existing.get();
                if (existingOrder.getRequestFingerprint() != null &&
                        !existingOrder.getRequestFingerprint().equals(order.getRequestFingerprint())) {
                    log.warn("Idempotency conflict (pre-check) | key={} | existingFingerprint={} | incomingFingerprint={}",
                            order.getSourceLocalRef(), existingOrder.getRequestFingerprint(), order.getRequestFingerprint());
                    throw new com.restaurant.pos.common.exception.DuplicateResourceException(
                            "Idempotency conflict: a different request with the same key has already been processed.");
                }
                log.info("Idempotent create hit (pre-check) | sourceLocalRef={}", order.getSourceLocalRef());
                return new com.restaurant.pos.order.dto.IdempotentCreateResult(hydrateOrder(existingOrder), false);
            }
            
            try {
                Order saved = self().createOrder(order);
                return new com.restaurant.pos.order.dto.IdempotentCreateResult(saved, true);
            } catch (org.springframework.dao.DataIntegrityViolationException e) {
                log.info("Data integrity violation on create, recovering... | sourceLocalRef={}", order.getSourceLocalRef());
                Optional<Order> winner = orderRepository.findByClientIdAndOrgIdAndSourceLocalRefAndOrderStatusNot(
                        clientId, resolvedOrgId, order.getSourceLocalRef(), "VOID");
                if (winner.isPresent()) {
                    Order winnerOrder = winner.get();
                    if (winnerOrder.getRequestFingerprint() != null &&
                            !winnerOrder.getRequestFingerprint().equals(order.getRequestFingerprint())) {
                        throw new com.restaurant.pos.common.exception.DuplicateResourceException(
                                "Idempotency conflict: a different request with the same key has already been processed.");
                    }
                    log.info("Idempotent create hit (recovery) | sourceLocalRef={}", order.getSourceLocalRef());
                    return new com.restaurant.pos.order.dto.IdempotentCreateResult(hydrateOrder(winnerOrder), false);
                }
                throw e;
            }
        }
        
        Order saved = self().createOrder(order);
        return new com.restaurant.pos.order.dto.IdempotentCreateResult(saved, true);
    }

    private OrderService self() {
        return applicationContext.getBean(OrderService.class);
    }

    @Transactional(readOnly = true)
    public Order getOrderBySourceLocalRef(UUID clientId, UUID orgId, String sourceLocalRef) {
        if (sourceLocalRef == null || sourceLocalRef.isBlank()) {
            return null;
        }
        return orderRepository.findByClientIdAndOrgIdAndSourceLocalRefAndOrderStatusNot(
                clientId, orgId, sourceLocalRef, "VOID")
                .map(this::hydrateOrder)
                .orElse(null);
    }

    @Transactional(readOnly = true)
    public Order getOrderBySourceLocalRef(String sourceLocalRef) {
        if (sourceLocalRef == null || sourceLocalRef.isBlank()) {
            return null;
        }
        return orderRepository.findByClientIdAndOrgIdAndSourceLocalRefAndOrderStatusNot(
                TenantContext.getCurrentTenant(), TenantContext.getCurrentOrg(), sourceLocalRef, "VOID")
                .map(this::hydrateOrder)
                .orElse(null);
    }

    /**
     * Returns all revisions (current + all VOID predecessors) for the given order,
     * ordered from oldest to newest by revisionNumber.
     */
    @Transactional(readOnly = true)
    public java.util.List<Order> getOrderRevisions(UUID id) {
        UUID clientId = TenantContext.getCurrentTenant();
        Order current = orderRepository.findByIdAndClientId(id, clientId)
                .orElseThrow(() -> new com.restaurant.pos.common.exception.BusinessException("Order not found"));
        // Base orderNo is the part before the first _VOID_ suffix
        String baseOrderNo = current.getOrderNo();
        if (baseOrderNo != null && baseOrderNo.contains("_VOID_")) {
            baseOrderNo = baseOrderNo.substring(0, baseOrderNo.indexOf("_VOID_"));
        }
        String voidPrefix = baseOrderNo + "_VOID_%";
        return orderRepository.findAllRevisionsByOrderNo(clientId, baseOrderNo, voidPrefix)
                .stream().map(this::hydrateOrder).toList();
    }

    private boolean isDiscountOnlyUpdate(Order oldOrder, Order updates) {
        if ("PAID".equalsIgnoreCase(oldOrder.getPaymentStatus()) 
                || "COMPLETED".equalsIgnoreCase(oldOrder.getOrderStatus())
                || "VOID".equalsIgnoreCase(oldOrder.getOrderStatus())) {
            return false;
        }
        if (updates.getLines() == null || updates.getLines().isEmpty()) {
            return true;
        }
        
        List<OrderLine> oldActiveLines = oldOrder.getLines() == null ? new java.util.ArrayList<>() : oldOrder.getLines().stream()
                .filter(ol -> "Y".equalsIgnoreCase(ol.getIsactive()))
                .toList();
        List<OrderLine> newActiveLines = updates.getLines().stream()
                .filter(ol -> "Y".equalsIgnoreCase(ol.getIsactive()))
                .toList();

        if (oldActiveLines.size() != newActiveLines.size()) {
            return false;
        }

        for (OrderLine upLine : newActiveLines) {
            OrderLine match = oldActiveLines.stream()
                .filter(ol -> (upLine.getId() != null && Objects.equals(ol.getId(), upLine.getId()))
                    || (Objects.equals(ol.getProductId(), upLine.getProductId())
                        && Objects.equals(ol.getVariantId(), upLine.getVariantId())))
                .findFirst()
                .orElse(null);
            if (match == null) {
                return false;
            }
            if (upLine.getQuantity() != null && match.getQuantity() != null 
                    && upLine.getQuantity().compareTo(match.getQuantity()) != 0) {
                return false;
            }
            if (upLine.getUnitPrice() != null && match.getUnitPrice() != null 
                    && upLine.getUnitPrice().compareTo(match.getUnitPrice()) != 0) {
                return false;
            }
        }

        if (updates.getTableId() != null && !Objects.equals(oldOrder.getTableId(), updates.getTableId())) {
            return false;
        }
        return true;
    }

    @Transactional
    public Order updateOrder(UUID id, Order updates) {
        Order oldOrder = getOrder(id);

        if (isDiscountOnlyUpdate(oldOrder, updates)) {
            if (updates.getOrderStatus() != null) oldOrder.setOrderStatus(updates.getOrderStatus());
            if (updates.getPaymentStatus() != null) oldOrder.setPaymentStatus(updates.getPaymentStatus());
            if (updates.getIsCredit() != null) oldOrder.setIsCredit(updates.getIsCredit());
            if (updates.getCreditCustomerId() != null) oldOrder.setCreditCustomerId(updates.getCreditCustomerId());
            if (updates.getCustomerId() != null) oldOrder.setCustomerId(updates.getCustomerId());
            if (updates.getCustomerName() != null) oldOrder.setCustomerName(updates.getCustomerName());
            if (updates.getCustomerPhone() != null) oldOrder.setCustomerPhone(updates.getCustomerPhone());
            if (updates.getCustomerIds() != null) oldOrder.setCustomerIds(updates.getCustomerIds());
            if (updates.getPaymentMethod() != null) oldOrder.setPaymentMethod(updates.getPaymentMethod());
            if (updates.getReference() != null) oldOrder.setReference(updates.getReference());
            if (updates.getDescription() != null) oldOrder.setDescription(updates.getDescription());
            if (updates.getOrderDiscountType() != null) oldOrder.setOrderDiscountType(updates.getOrderDiscountType());
            if (updates.getOrderDiscountValue() != null) oldOrder.setOrderDiscountValue(updates.getOrderDiscountValue());
            if (updates.getDiscountSource() != null) oldOrder.setDiscountSource(updates.getDiscountSource());
            if (updates.getDiscountCalculationVersion() != null) oldOrder.setDiscountCalculationVersion(updates.getDiscountCalculationVersion());
            if (updates.getRoundOffAmount() != null) oldOrder.setRoundOffAmount(updates.getRoundOffAmount());
            if (updates.getRoundOffMode() != null) oldOrder.setRoundOffMode(updates.getRoundOffMode());

            if (updates.getLines() != null) {
                for (OrderLine upLine : updates.getLines()) {
                    OrderLine match = oldOrder.getLines().stream()
                        .filter(ol -> (upLine.getId() != null && Objects.equals(ol.getId(), upLine.getId()))
                            || (Objects.equals(ol.getProductId(), upLine.getProductId())
                                && Objects.equals(ol.getVariantId(), upLine.getVariantId())))
                        .findFirst()
                        .orElse(null);
                    if (match != null) {
                        if (upLine.getDiscountAmount() != null) match.setDiscountAmount(upLine.getDiscountAmount());
                        if (upLine.getManualDiscountAmount() != null) match.setManualDiscountAmount(upLine.getManualDiscountAmount());
                        if (upLine.getManualDiscountPercent() != null) match.setManualDiscountPercent(upLine.getManualDiscountPercent());
                        if (upLine.getAllocatedOrderDiscount() != null) match.setAllocatedOrderDiscount(upLine.getAllocatedOrderDiscount());
                        if (upLine.getTaxAmount() != null) match.setTaxAmount(upLine.getTaxAmount());
                        if (upLine.getLineTotal() != null) match.setLineTotal(upLine.getLineTotal());
                        if (upLine.getGrossLineAmount() != null) match.setGrossLineAmount(upLine.getGrossLineAmount());
                        if (upLine.getUnitPriceExTax() != null) match.setUnitPriceExTax(upLine.getUnitPriceExTax());
                        if (match.getUnitPriceExTax() == null) match.setUnitPriceExTax(upLine.getUnitPriceExTax());
                        if (upLine.getTaxableAmount() != null) match.setTaxableAmount(upLine.getTaxableAmount());
                        if (upLine.getTaxType() != null) match.setTaxType(upLine.getTaxType());
                        if (upLine.getTaxSnapshotRate() != null) match.setTaxSnapshotRate(upLine.getTaxSnapshotRate());
                        if (upLine.getTaxCode() != null) match.setTaxCode(upLine.getTaxCode());
                        if (upLine.getTaxName() != null) match.setTaxName(upLine.getTaxName());
                    }
                }
            }

            hydrateOrderLines(oldOrder);
            recalculateOrderTotals(oldOrder);
            prepareCreditCustomer(oldOrder, Boolean.TRUE.equals(oldOrder.getIsCredit()));
            prepareCustomerFields(oldOrder);

            Order saved = orderRepository.saveAndFlush(oldOrder);
            linkCustomersToSavedOrder(saved);

            List<Invoice> existingInvoices = invoiceRepository.findByOrderId(saved.getId());
            for (Invoice existingInv : existingInvoices) {
                if (!"VOID".equalsIgnoreCase(existingInv.getStatus())) {
                    existingInv.setTotalAmount(saved.getGrandTotal().subtract(saved.getRoundOffAmount() != null ? saved.getRoundOffAmount() : BigDecimal.ZERO));
                    existingInv.setAmountDue(saved.getGrandTotal().subtract(saved.getRoundOffAmount() != null ? saved.getRoundOffAmount() : BigDecimal.ZERO));
                    existingInv.setTotalDiscountAmount(saved.getTotalDiscountAmount());
                    existingInv.setTotalTaxAmount(saved.getTotalTaxAmount());
                    existingInv.setGrossAmount(saved.getGrossAmount());
                    existingInv.setTaxableAmount(saved.getGrossAmount().subtract(saved.getTotalDiscountAmount()));

                    if (existingInv.getLines() != null) {
                        for (InvoiceLine invLine : existingInv.getLines()) {
                            OrderLine ol = saved.getLines().stream()
                                .filter(l -> Objects.equals(l.getProductId(), invLine.getProductId())
                                    && Objects.equals(l.getVariantId(), invLine.getVariantId()))
                                .findFirst()
                                .orElse(null);
                            if (ol != null) {
                                invLine.setDiscountAmount(ol.getDiscountAmount());
                                invLine.setManualDiscountAmount(ol.getManualDiscountAmount());
                                invLine.setManualDiscountPercent(ol.getManualDiscountPercent());
                                invLine.setAllocatedOrderDiscount(ol.getAllocatedOrderDiscount());
                                invLine.setTaxAmount(ol.getTaxAmount());
                                invLine.setLineTotal(ol.getLineTotal());
                                invLine.setGrossLineAmount(ol.getGrossLineAmount());
                                invLine.setUnitPriceExTax(ol.getUnitPriceExTax());
                                invLine.setTaxableAmount(ol.getTaxableAmount());
                            }
                        }
                    }

                    invoiceRepository.save(existingInv);
                    accountingPostingService.replaceInvoiceJournal(saved, existingInv,
                            "Invoice amount corrected after discount/roundoff update");
                }
            }

            if ("PAID".equalsIgnoreCase(saved.getPaymentStatus())) {
                List<Payment> existingPayments = paymentRepository.findByOrderId(saved.getId());
                if (!existingPayments.isEmpty()) {
                    for (Payment existingPay : existingPayments) {
                        if (!"VOID".equalsIgnoreCase(existingPay.getDocStatus())) {
                            existingPay.setAmountPaid(saved.getGrandTotal());
                            existingPay.setInvoiceTotal(saved.getGrandTotal());
                            existingPay.setRoundOffAmount(saved.getRoundOffAmount());
                            paymentRepository.save(existingPay);
                            accountingPostingService.reversePayment(existingPay, "Payment amount corrected after discount/roundoff update");
                            accountingPostingService.postPayment(saved, existingPay);
                        }
                    }
                } else {
                    String salePaymentMethod = saved.getReference() != null ? saved.getReference() : "CASH";
                    generatePayment(saved, salePaymentMethod, null);
                }
            }

            return hydrateOrder(saved);
        }

        // 1. Create a deep copy of the old order as a VOID record
        String originalOrderNo = oldOrder.getOrderNo();
        UUID oldTableId = oldOrder.getTableId();
        oldOrder.setOrderNo(
                originalOrderNo + "_VOID_" + (oldOrder.getRevisionNumber() != null ? oldOrder.getRevisionNumber() : 0));
        if (oldOrder.getSourceOperationId() != null) {
            oldOrder.setSourceOperationId(
                    oldOrder.getSourceOperationId() + "_VOID_" + (oldOrder.getRevisionNumber() != null ? oldOrder.getRevisionNumber() : 0));
        }
        oldOrder.setOrderStatus("VOID");
        oldOrder.setIsactive("N");
        oldOrder.setTableId(null);
        oldOrder.setTableNumber(null);
        orderRepository.saveAndFlush(oldOrder);

        // 2. VOID the linked invoice
        List<UUID> oldInvoiceIdList = new java.util.ArrayList<>();
        String originalInvoiceNo = null;
        for (Invoice inv : invoiceRepository.findByOrderId(id)) {
            oldInvoiceIdList.add(inv.getId());
            if (originalInvoiceNo == null) {
                originalInvoiceNo = inv.getInvoiceNo();
            }
            accountingPostingService.reverseInvoice(inv, "Order revised");
            inv.setInvoiceNo(inv.getInvoiceNo() + "_VOID_"
                    + (oldOrder.getRevisionNumber() != null ? oldOrder.getRevisionNumber() : 0));
            inv.setStatus("VOID");
            invoiceRepository.saveAndFlush(inv);
        }

        List<PaymentSplit> oldSplits = new java.util.ArrayList<>();
        String originalPaymentNo = null;
        for (Payment payment : paymentRepository.findByOrderId(id)) {
            oldSplits.addAll(paymentSplitRepository.findByPaymentIdOrderByCreatedAtAsc(payment.getId()));
            if (originalPaymentNo == null) {
                originalPaymentNo = payment.getReferenceNo();
            }
            accountingPostingService.reversePayment(payment, "Order revised");
            payment.setReferenceNo((payment.getReferenceNo() != null ? payment.getReferenceNo() : "PAYMENT")
                    + "_VOID_" + (oldOrder.getRevisionNumber() != null ? oldOrder.getRevisionNumber() : 0));
            payment.setDocStatus("VOID");
            payment.setIsactive("N");
            paymentRepository.saveAndFlush(payment);
        }

        // 3. Create the correct entity subtype to preserve the JPA discriminator value.
        // Using Order.builder().build() always creates the base Order class
        // (@DiscriminatorValue("SALE")),
        // which would corrupt Purchase/Expense orders in the database.
        Order newOrder;
        OrderType preservedType = oldOrder.getOrderType();
        if (preservedType == OrderType.PURCHASE) {
            newOrder = new com.restaurant.pos.purchasing.domain.PurchaseOrder();
        } else {
            newOrder = new Order();
        }

        // Copy core identification
        newOrder.setId(UUID.randomUUID());
        newOrder.setOrderNo(originalOrderNo);
        newOrder.setOrderType(oldOrder.getOrderType());
        newOrder.setTerminalId(oldOrder.getTerminalId());
        newOrder.setOrderSource(oldOrder.getOrderSource());
        newOrder.setOriginalOrderId(oldOrder.getId());
        newOrder.setRevisionNumber((oldOrder.getRevisionNumber() != null ? oldOrder.getRevisionNumber() : 0) + 1);

        newOrder.setSourceDeviceId(oldOrder.getSourceDeviceId());
        newOrder.setSourceTerminalId(oldOrder.getSourceTerminalId());
        newOrder.setSourceOperationId(oldOrder.getSourceOperationId());
        newOrder.setSourceOfflineId(oldOrder.getSourceOfflineId());
        newOrder.setSourceLocalRef(oldOrder.getSourceLocalRef());
        newOrder.setOfflineCreatedAt(oldOrder.getOfflineCreatedAt());
        newOrder.setSyncOrigin(oldOrder.getSyncOrigin());

        // Status
        newOrder.setOrderStatus(
                updates.getOrderStatus() != null ? updates.getOrderStatus() : oldOrder.getOrderStatus());
        newOrder.setPaymentStatus(
                updates.getPaymentStatus() != null ? updates.getPaymentStatus() : oldOrder.getPaymentStatus());

        // Parties & references (merge from updates, fallback to old)
        newOrder.setVendorId(updates.getVendorId() != null ? updates.getVendorId() : oldOrder.getVendorId());
        newOrder.setCustomerId(updates.getCustomerId() != null ? updates.getCustomerId() : oldOrder.getCustomerId());
        newOrder.setIsCredit(updates.getIsCredit() != null ? updates.getIsCredit() : oldOrder.getIsCredit());
        newOrder.setCreditCustomerId(
                updates.getCreditCustomerId() != null ? updates.getCreditCustomerId() : oldOrder.getCreditCustomerId());
        newOrder.setCustomerName(
                updates.getCustomerName() != null ? updates.getCustomerName() : oldOrder.getCustomerName());
        newOrder.setCustomerPhone(
                updates.getCustomerPhone() != null ? updates.getCustomerPhone() : oldOrder.getCustomerPhone());
        newOrder.setCustomerIds(
                updates.getCustomerIds() != null ? updates.getCustomerIds() : oldOrder.getCustomerIds());
        newOrder.setWarehouseId(
                updates.getWarehouseId() != null ? updates.getWarehouseId() : oldOrder.getWarehouseId());
        newOrder.setPricelistId(
                updates.getPricelistId() != null ? updates.getPricelistId() : oldOrder.getPricelistId());
        newOrder.setCurrencyId(updates.getCurrencyId() != null ? updates.getCurrencyId() : oldOrder.getCurrencyId());
        newOrder.setPaymentMethod(
                updates.getPaymentMethod() != null ? updates.getPaymentMethod() : oldOrder.getPaymentMethod());
        newOrder.setReference(updates.getReference() != null ? updates.getReference() : oldOrder.getReference());

        // Dates & addresses
        newOrder.setOrderDate(updates.getOrderDate() != null ? updates.getOrderDate() : oldOrder.getOrderDate());
        newOrder.setFulfillmentType(
                updates.getFulfillmentType() != null ? updates.getFulfillmentType() : oldOrder.getFulfillmentType());
        newOrder.setTableNumber(updates.getTableNumber());
        newOrder.setTableId(updates.getTableId());

        // Financial totals
        newOrder.setTotalAmount(
                updates.getTotalAmount() != null ? updates.getTotalAmount() : oldOrder.getTotalAmount());
        newOrder.setTotalTaxAmount(
                updates.getTotalTaxAmount() != null ? updates.getTotalTaxAmount() : oldOrder.getTotalTaxAmount());
        newOrder.setTotalDiscountAmount(updates.getTotalDiscountAmount() != null ? updates.getTotalDiscountAmount()
                : oldOrder.getTotalDiscountAmount());
        newOrder.setGrandTotal(updates.getGrandTotal() != null ? updates.getGrandTotal() : oldOrder.getGrandTotal());
        newOrder.setRoundOffAmount(
                updates.getRoundOffAmount() != null ? updates.getRoundOffAmount() : oldOrder.getRoundOffAmount());
        newOrder.setGrossAmount(
                updates.getGrossAmount() != null ? updates.getGrossAmount() : oldOrder.getGrossAmount());
        newOrder.setOrderDiscountType(
                updates.getOrderDiscountType() != null ? updates.getOrderDiscountType() : oldOrder.getOrderDiscountType());
        newOrder.setOrderDiscountValue(
                updates.getOrderDiscountValue() != null ? updates.getOrderDiscountValue() : oldOrder.getOrderDiscountValue());
        newOrder.setDiscountSource(
                updates.getDiscountSource() != null ? updates.getDiscountSource() : oldOrder.getDiscountSource());
        newOrder.setDiscountCalculationVersion(
                updates.getDiscountCalculationVersion() != null ? updates.getDiscountCalculationVersion()
                        : oldOrder.getDiscountCalculationVersion());
        newOrder.setDescription(
                updates.getDescription() != null ? updates.getDescription() : oldOrder.getDescription());
        newOrder.setSkipAutoPrintKinds(updates.getSkipAutoPrintKinds());

        // Tenant fields
        newOrder.setClientId(oldOrder.getClientId());
        newOrder.setOrgId(oldOrder.getOrgId());

        // Lines — from the request payload (or fall back to old lines)
        if (updates.getLines() != null && !updates.getLines().isEmpty()) {
            updates.getLines().forEach(newOrder::addLine);
        } else if (oldOrder.getLines() != null && !oldOrder.getLines().isEmpty()) {
            // Copy lines from old order when updates don't include them
            for (com.restaurant.pos.order.domain.OrderLine oldLine : oldOrder.getLines()) {
                com.restaurant.pos.order.domain.OrderLine copy = new com.restaurant.pos.order.domain.OrderLine();
                copy.setProductId(oldLine.getProductId());
                copy.setVariantId(oldLine.getVariantId());
                copy.setProductName(oldLine.getProductName());
                copy.setCategoryName(oldLine.getCategoryName());
                copy.setIsPackagedGood(oldLine.getIsPackagedGood());
                copy.setQuantity(oldLine.getQuantity());
                copy.setUnitOfMeasure(oldLine.getUnitOfMeasure());
                copy.setUnitPrice(oldLine.getUnitPrice());
                copy.setTaxRate(oldLine.getTaxRate());
                copy.setTaxAmount(oldLine.getTaxAmount());
                copy.setDiscountAmount(oldLine.getDiscountAmount());
                copy.setLineTotal(oldLine.getLineTotal());
                // ─── GST Enrichment Fields (V1_110) ───────────────────────────────────
                // Must be copied to preserve correct tax-exclusive discount calculation
                // in AccountingService.calculateTaxExclusiveDiscount. Without these,
                // settled kitchen orders report inflated gross sales and wrong discounts.
                copy.setGrossLineAmount(oldLine.getGrossLineAmount());
                copy.setUnitPriceExTax(oldLine.getUnitPriceExTax());
                copy.setTaxableAmount(oldLine.getTaxableAmount());
                copy.setTaxType(oldLine.getTaxType());
                copy.setTaxSnapshotRate(oldLine.getTaxSnapshotRate());
                copy.setTaxCode(oldLine.getTaxCode());
                copy.setTaxName(oldLine.getTaxName());
                copy.setManualDiscountAmount(oldLine.getManualDiscountAmount());
                copy.setManualDiscountPercent(oldLine.getManualDiscountPercent());
                copy.setAllocatedOrderDiscount(oldLine.getAllocatedOrderDiscount());
                // Preserve transient client-generated line identity for stable recalculation mapping
                copy.setClientLineId(oldLine.getClientLineId());
                // ──────────────────────────────────────────────────────────────────────
                newOrder.addLine(copy);
            }
        }
        hydrateOrderLines(newOrder);
        recalculateOrderTotals(newOrder);
        prepareCreditCustomer(newOrder, Boolean.TRUE.equals(newOrder.getIsCredit()));
        prepareCustomerFields(newOrder);

        // SNAPSHOT the old order's lines BEFORE saving the new order,
        // because saveAndFlush will merge and mutate the OrderLine entities in the DB,
        // which would cause oldOrder.getLines() to reflect the new state.
        Order oldOrderSnapshot = new Order();
        if (oldOrder.getLines() != null) {
            for (com.restaurant.pos.order.domain.OrderLine oldLine : oldOrder.getLines()) {
                com.restaurant.pos.order.domain.OrderLine copy = new com.restaurant.pos.order.domain.OrderLine();
                copy.setProductId(oldLine.getProductId());
                copy.setVariantId(oldLine.getVariantId());
                copy.setProductName(oldLine.getProductName());
                copy.setCategoryName(oldLine.getCategoryName());
                copy.setIsPackagedGood(oldLine.getIsPackagedGood());
                copy.setQuantity(oldLine.getQuantity());
                copy.setUnitOfMeasure(oldLine.getUnitOfMeasure());
                copy.setUnitPrice(oldLine.getUnitPrice());
                copy.setTaxRate(oldLine.getTaxRate());
                copy.setTaxAmount(oldLine.getTaxAmount());
                copy.setDiscountAmount(oldLine.getDiscountAmount());
                copy.setLineTotal(oldLine.getLineTotal());
                oldOrderSnapshot.addLine(copy);
            }
        }

        List<com.restaurant.pos.order.domain.OrderLine> addedLines = new java.util.ArrayList<>();
        List<com.restaurant.pos.order.domain.OrderLine> removedLines = new java.util.ArrayList<>();
        calculateKotDelta(oldOrderSnapshot, newOrder, addedLines, removedLines);

        Order saved = orderRepository.saveAndFlush(newOrder);
        linkCustomersToSavedOrder(saved);

        saved.setRoundOffAmount(newOrder.getRoundOffAmount());

        // 4. Generate new ERP documents (Invoice/Payment)
        UUID oldInvId = oldInvoiceIdList.isEmpty() ? null : oldInvoiceIdList.get(0);
        if (shouldGenerateInvoice(saved)) {
            generateInvoice(saved, oldInvId, originalInvoiceNo);
        }
        // Resolve payment method safely
        String paymentMethod = newOrder.getPaymentMethod();
        if (paymentMethod == null || paymentMethod.isBlank()) {
            paymentMethod = saved.getPaymentMethod();
        }
        if (paymentMethod == null || paymentMethod.isBlank()) {
            paymentMethod = "CASH";
        }
        boolean isCredit = Boolean.TRUE.equals(saved.getIsCredit()) || "CREDIT".equalsIgnoreCase(paymentMethod);

        if (saved.getOrderType() == OrderType.PURCHASE) {
            if (!"DRAFT".equalsIgnoreCase(saved.getOrderStatus()) && !isCredit) {
                generatePayment(saved, paymentMethod, originalPaymentNo);
            }
            if ("COMPLETED".equalsIgnoreCase(saved.getOrderStatus())) {
                processInventoryForOrder(saved);
            }
        } else {
            // Sales
            if ("PAID".equalsIgnoreCase(saved.getPaymentStatus())) {
                String salePaymentMethod = saved.getReference() != null ? saved.getReference() : "CASH";
                List<OrderSettleRequest.PaymentSplitRequest> paymentSplits = new java.util.ArrayList<>();
                if ("MIXED".equalsIgnoreCase(normalizePaymentMethod(salePaymentMethod)) && !oldSplits.isEmpty()) {
                    BigDecimal oldSplitsTotal = oldSplits.stream()
                            .map(PaymentSplit::getAmount)
                            .filter(Objects::nonNull)
                            .reduce(BigDecimal.ZERO, BigDecimal::add);
                    BigDecimal newTotal = saved.getGrandTotal();
                    if (oldSplitsTotal.compareTo(BigDecimal.ZERO) > 0 && newTotal != null) {
                        BigDecimal ratio = newTotal.divide(oldSplitsTotal, 10, RoundingMode.HALF_UP);
                        BigDecimal runningSum = BigDecimal.ZERO;
                        for (int i = 0; i < oldSplits.size(); i++) {
                            PaymentSplit oldSplit = oldSplits.get(i);
                            BigDecimal newSplitAmt;
                            if (i == oldSplits.size() - 1) {
                                newSplitAmt = newTotal.subtract(runningSum);
                            } else {
                                newSplitAmt = oldSplit.getAmount().multiply(ratio).setScale(2, RoundingMode.HALF_UP);
                                runningSum = runningSum.add(newSplitAmt);
                            }
                            OrderSettleRequest.PaymentSplitRequest req = new OrderSettleRequest.PaymentSplitRequest();
                            req.setPaymentMethod(oldSplit.getPaymentMethod());
                            req.setAmount(newSplitAmt);
                            req.setReferenceNo(oldSplit.getReferenceNo());
                            paymentSplits.add(req);
                        }
                    }
                }
                
                if (!paymentSplits.isEmpty()) {
                    generatePayment(saved, salePaymentMethod, originalPaymentNo, saved.getGrandTotal(), null, null, null, paymentSplits);
                } else {
                    generatePayment(saved, salePaymentMethod, originalPaymentNo);
                }
                accountingPostingService.postSaleCogs(saved);
            } else if (isCompletedCreditSale(saved)) {
                accountingPostingService.postSaleCogs(saved);
            }
        }

        if (oldTableId != null && !oldTableId.equals(saved.getTableId())) {
            setTableStatus(oldTableId, "AVAILABLE", saved.getOrgId());
        }
        handleTableStatus(saved);

        // Inventory Hook: If PURCHASE order is COMPLETED, update stock
        if (saved.getOrderType() == OrderType.PURCHASE && "COMPLETED".equalsIgnoreCase(saved.getOrderStatus())) {
            processInventoryForOrder(saved);
        }

        Order hydrated = hydrateOrder(saved);
        hydrated.setSkipAutoPrintKinds(newOrder.getSkipAutoPrintKinds());
        enqueueCloudPrintJobs(hydrated, addedLines, removedLines);
        return hydrated;
    }

    private void validateStatusTransition(String from, String to) {
        String fromUpper = from == null ? "DRAFT" : from.toUpperCase();
        String toUpper = to == null ? "DRAFT" : to.toUpperCase();

        if (fromUpper.equals(toUpper)) {
            return;
        }

        if (FINANCIAL_STATUS_NAMES.contains(fromUpper)) {
            throw new BusinessException("Cannot modify status of a completed, billed, or cancelled order.");
        }

        if (FINANCIAL_STATUS_NAMES.contains(toUpper)) {
            throw new BusinessException("Status transition to '" + toUpper + "' must go through dedicated command endpoints (/bill, /settle, /cancel, /complete-credit).");
        }

        Set<String> allowed = ALLOWED_OPERATIONAL_TRANSITIONS.get(fromUpper);
        if (allowed == null || !allowed.contains(toUpper)) {
            throw new BusinessException("Invalid order status transition from '" + fromUpper + "' to '" + toUpper + "'.");
        }
    }

    @Transactional
    @org.springframework.retry.annotation.Retryable(value = {
            org.springframework.orm.ObjectOptimisticLockingFailureException.class }, maxAttempts = 3, backoff = @org.springframework.retry.annotation.Backoff(delay = 50))
    public Order updateOrderStatus(UUID id, String status, String paymentStatus, String description) {
        Order order = getOrder(id);
        String oldStatus = order.getOrderStatus();
        if (status != null && !status.equalsIgnoreCase(oldStatus)) {
            validateStatusTransition(oldStatus, status.toUpperCase());
            order.setOrderStatus(status);
        }
        if (paymentStatus != null)
            order.setPaymentStatus(paymentStatus);
        if (description != null)
            order.setDescription(description);
        prepareCreditCustomer(order, Boolean.TRUE.equals(order.getIsCredit()));

        if ("CONFIRMED".equalsIgnoreCase(status) && "PENDING".equalsIgnoreCase(oldStatus)
                && order.getCustomerId() == null) {
            registerCustomerForAcceptedDeliveryOrder(order);
        }

        if ("CONFIRMED".equalsIgnoreCase(status) && order.getTerminalId() == null) {
            UUID currentTerminalId = TenantContext.getCurrentTerminal();
            if (currentTerminalId != null) {
                order.setTerminalId(currentTerminalId);
                if (order.getSourceTerminalId() == null) {
                    order.setSourceTerminalId(currentTerminalId);
                }
            }
        }
        Order result = orderRepository.saveAndFlush(order);

        if (shouldGenerateInvoice(result)) {
            generateInvoice(result);
        }

        // Resolve payment method safely
        String paymentMethod = order.getPaymentMethod();
        if (paymentMethod == null || paymentMethod.isBlank()) {
            paymentMethod = result.getPaymentMethod();
        }
        if (paymentMethod == null || paymentMethod.isBlank()) {
            paymentMethod = "CASH";
        }
        boolean isCredit = Boolean.TRUE.equals(result.getIsCredit()) || "CREDIT".equalsIgnoreCase(paymentMethod);

        if (result.getOrderType() == OrderType.PURCHASE) {
            if (!"DRAFT".equalsIgnoreCase(result.getOrderStatus()) && !isCredit) {
                generatePayment(result, paymentMethod);
            }
        } else {
            // Sales
            if ("COMPLETED".equalsIgnoreCase(result.getOrderStatus())
                    && "PAID".equalsIgnoreCase(result.getPaymentStatus())) {
                String salePaymentMethod = result.getReference() != null ? result.getReference() : "CASH";
                generatePayment(result, salePaymentMethod);
                accountingPostingService.postSaleCogs(result);
            } else if (isCompletedCreditSale(result)) {
                accountingPostingService.postSaleCogs(result);
            }
        }

        handleTableStatus(result);

        if (result.getOrderType() == OrderType.PURCHASE && "COMPLETED".equalsIgnoreCase(result.getOrderStatus())) {
            processInventoryForOrder(result);
        }
        Order hydrated = hydrateOrder(result);
        enqueueCloudPrintJobs(hydrated);
        publishOrderStatusUpdate(result);
        return hydrated;
    }
    private void registerCustomerForAcceptedDeliveryOrder(Order order) {
        String description = order.getDescription();
        if (description == null || description.isBlank()) {
            return;
        }

        String email = parseField(description, "email");
        String name = parseField(description, "name");
        String phone = parseField(description, "phone");
        String address = parseField(description, "address");

        String normalizedPhone = phone != null ? phone.trim().replaceAll("[\\s()\\-]", "") : null;
        if (normalizedPhone == null || normalizedPhone.isBlank()) {
            return;
        }

        UUID clientId = order.getClientId();
        java.util.Optional<Customer> existingOpt = customerRepository
                .findFirstByPhoneAndClientIdOrderByCreatedAtAsc(normalizedPhone, clientId);
        Customer customer;
        if (existingOpt.isPresent()) {
            customer = existingOpt.get();
            if ((customer.getName() == null || customer.getName().isBlank()) && name != null && !name.isBlank()) {
                customer.setName(name);
            }
            if ((customer.getEmail() == null || customer.getEmail().isBlank()) && email != null && !email.isBlank()) {
                customer.setEmail(email);
            }
            if ((customer.getAddress() == null || customer.getAddress().isBlank()) && address != null
                    && !address.isBlank()) {
                customer.setAddress(address);
            }
        } else {
            customer = Customer.builder()
                    .name(name == null || name.isBlank() ? "Guest" : name)
                    .phone(normalizedPhone)
                    .email(email)
                    .address(address)
                    .customerCategory("REGULAR")
                    .orderLinks(new java.util.ArrayList<>())
                    .build();
            customer.setClientId(clientId);
            customer.setOrgId(null);
        }

        if (customer.getOrderLinks() == null) {
            customer.setOrderLinks(new java.util.ArrayList<>());
        }
        boolean alreadyLinked = customer.getOrderLinks().stream()
                .anyMatch(link -> java.util.Objects.equals(order.getId(), link.getOrderId()));
        if (!alreadyLinked) {
            customer.getOrderLinks().add(Customer.OrderLink.builder()
                    .orderId(order.getId())
                    .isPrimary(true)
                    .attachedAt(java.time.Instant.now().toString())
                    .build());
        }
        Customer savedCustomer = customerRepository.save(customer);
        order.setCustomerId(savedCustomer.getId());
    }

    private String parseField(String text, String field) {
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile(field + ":(.*?)(?=\\s+\\w+:|$)");
        java.util.regex.Matcher matcher = pattern.matcher(text);
        if (matcher.find()) {
            return matcher.group(1).trim();
        }
        return null;
    }

    @org.springframework.retry.annotation.Retryable(value = {
            org.springframework.orm.ObjectOptimisticLockingFailureException.class }, maxAttempts = 3, backoff = @org.springframework.retry.annotation.Backoff(delay = 50))
    public Order updateOrderStatus(UUID id, String status) {
        return updateOrderStatus(id, status, null, null);
    }

    @org.springframework.retry.annotation.Retryable(value = {
            org.springframework.orm.ObjectOptimisticLockingFailureException.class }, maxAttempts = 3, backoff = @org.springframework.retry.annotation.Backoff(delay = 50))
    public Order updateOrderStatus(UUID id, OrderStatus status, PaymentStatus paymentStatus, String description) {
        return updateOrderStatus(
                id,
                status != null ? status.name() : null,
                paymentStatus != null ? paymentStatus.name() : null,
                description);
    }

    @org.springframework.retry.annotation.Retryable(value = {
            org.springframework.orm.ObjectOptimisticLockingFailureException.class }, maxAttempts = 3, backoff = @org.springframework.retry.annotation.Backoff(delay = 50))
    public Order updateOrderStatus(UUID id, OrderStatus status) {
        return updateOrderStatus(id, status, null, null);
    }

    @Transactional
    public Order billOrder(UUID id) {
        return billOrder(id, null);
    }

    @Transactional
    public Order billOrder(UUID id, List<String> skipAutoPrintKinds) {
        Order order = getOrder(id);
        ensureOrderCanChange(order, "bill");

        if (order.getOrderType() != OrderType.PURCHASE) {
            order.setOrderStatus("BILLED");
        }
        if (!"PAID".equalsIgnoreCase(order.getPaymentStatus())) {
            order.setPaymentStatus("PENDING");
        }

        Order saved = orderRepository.save(order);
        generateInvoice(saved);
        handleTableStatus(saved);
        Order hydrated = hydrateOrder(saved);
        hydrated.setSkipAutoPrintKinds(skipAutoPrintKinds);
        enqueueCloudPrintJobs(hydrated);
        return hydrated;
    }

    @Transactional
    public Order settleOrder(UUID id, OrderSettleRequest request) {
        Order order = getOrder(id);
        ensureOrderCanChange(order, "settle");

        OrderSettleRequest safeRequest = request == null ? new OrderSettleRequest() : request;
        String paymentMethod = normalizePaymentMethod(safeRequest.getPaymentMethod());
        if (hasExplicitPaymentSplits(safeRequest)) {
            paymentMethod = "MIXED";
        }
        
        BigDecimal discountAmount = moneyValue(safeRequest.getDiscountAmount());
        BigDecimal roundOffAmount = moneyValue(safeRequest.getRoundOffAmount());

        // First, calculate line-level discounts only by setting order discount to zero
        order.setOrderDiscountValue(BigDecimal.ZERO);
        recalculateOrderTotals(order);
        BigDecimal lineDiscountSum = order.getTotalDiscountAmount() != null ? order.getTotalDiscountAmount() : BigDecimal.ZERO;

        BigDecimal requestedTotalDiscount = moneyValue(safeRequest.getDiscountAmount());
        BigDecimal orderDiscountValue = requestedTotalDiscount.subtract(lineDiscountSum).max(BigDecimal.ZERO);

        order.setOrderDiscountType("AMOUNT");
        order.setOrderDiscountValue(orderDiscountValue);
        order.setDiscountSource(com.restaurant.pos.order.domain.DiscountSource.MANUAL);
        order.setRoundOffAmount(roundOffAmount);
        order.setRoundOffMode(safeRequest.getRoundOffMode());

        recalculateOrderTotals(order);

        order.setReference(paymentMethod);
        order.setOrderStatus("COMPLETED");
        order.setPaymentStatus("PAID");

        String settlementDescription = buildSettlementDescription(safeRequest, paymentMethod);
        if (!settlementDescription.isBlank()) {
            order.setDescription(appendDescription(order.getDescription(), settlementDescription));
        }

        Order saved = orderRepository.save(order);

        // Fix: update existing invoice if discount/roundoff changed the total
        Invoice linkedInvoice = null;
        List<Invoice> existingInvoices = invoiceRepository.findByOrderId(saved.getId());
        for (Invoice existingInv : existingInvoices) {
            if (!"VOID".equalsIgnoreCase(existingInv.getStatus())) {
                existingInv.setTotalAmount(saved.getGrandTotal().subtract(roundOffAmount));
                existingInv.setAmountDue(saved.getGrandTotal().subtract(roundOffAmount));
                invoiceRepository.save(existingInv);
                accountingPostingService.replaceInvoiceJournal(saved, existingInv,
                        "Invoice amount corrected after discount/roundoff");
                linkedInvoice = existingInv;
            }
        }
        
        saved.setRoundOffAmount(roundOffAmount);
        Invoice generatedInv = generateInvoice(saved);
        if (linkedInvoice == null) {
            linkedInvoice = generatedInv;
        }

        BigDecimal payable = saved.getGrandTotal();
        BigDecimal amountPaid = safeRequest.getAmountPaid() != null
                ? moneyValue(safeRequest.getAmountPaid())
                : payable;
        BigDecimal settleRoundOff = moneyValue(safeRequest.getRoundOffAmount());

        // Validate payment invariant: amount_paid = invoice_total + round_off_amount
        BigDecimal invoiceTotal = linkedInvoice != null
                ? moneyValue(linkedInvoice.getTotalAmount())
                : moneyValue(saved.getGrandTotal()).subtract(settleRoundOff);
        validatePaymentInvariant(invoiceTotal, settleRoundOff, amountPaid);

        generatePayment(saved, paymentMethod, null, amountPaid, settlementDescription,
                safeRequest.getCashAmount(), safeRequest.getOnlineAmount(), safeRequest.getPaymentSplits(),
                settleRoundOff);
        accountingPostingService.postSaleCogs(saved);

        handleTableStatus(saved);

        if (saved.getOrderType() == OrderType.PURCHASE && "COMPLETED".equalsIgnoreCase(saved.getOrderStatus())) {
            processInventoryForOrder(saved);
        }

        Order hydrated = hydrateOrder(saved);
        hydrated.setSkipAutoPrintKinds(safeRequest.getSkipAutoPrintKinds());
        enqueueCloudPrintJobs(hydrated);

        try {
            if (hydrated.getOrderType() == OrderType.SALE) {
                pushNotificationService.sendOrderSettledPush(hydrated);
            }
        } catch (Exception ex) {
            log.error("Failed to send push notification for settled order {}", hydrated.getId(), ex);
        }

        publishOrderStatusUpdate(saved);
        return hydrated;
    }

    @Transactional
    public Order completeCreditOrder(UUID id, OrderCreditCompletionRequest request) {
        Order order = getOrder(id);
        ensureOrderCanChange(order, "settle");
        if ("PAID".equalsIgnoreCase(order.getPaymentStatus())) {
            throw new BusinessException("Paid orders cannot be converted to credit");
        }

        OrderCreditCompletionRequest safeRequest = request == null ? new OrderCreditCompletionRequest() : request;
        order.setCreditCustomerId(safeRequest.getCreditCustomerId() != null ? safeRequest.getCreditCustomerId()
                : order.getCreditCustomerId());
        prepareCreditCustomer(order, true);
        prepareCustomerFields(order);

        BigDecimal discountAmount = moneyValue(safeRequest.getDiscountAmount());
        BigDecimal roundOffAmount = moneyValue(safeRequest.getRoundOffAmount());

        // First, calculate line-level discounts only by setting order discount to zero
        order.setOrderDiscountValue(BigDecimal.ZERO);
        recalculateOrderTotals(order);
        BigDecimal lineDiscountSum = order.getTotalDiscountAmount() != null ? order.getTotalDiscountAmount() : BigDecimal.ZERO;

        BigDecimal requestedTotalDiscount = moneyValue(safeRequest.getDiscountAmount());
        BigDecimal orderDiscountValue = requestedTotalDiscount.subtract(lineDiscountSum).max(BigDecimal.ZERO);

        order.setOrderDiscountType("AMOUNT");
        order.setOrderDiscountValue(orderDiscountValue);
        order.setDiscountSource(com.restaurant.pos.order.domain.DiscountSource.MANUAL);
        order.setRoundOffAmount(roundOffAmount);
        order.setRoundOffMode(safeRequest.getRoundOffMode());

        recalculateOrderTotals(order);

        order.setReference("CREDIT");
        order.setOrderStatus("COMPLETED");
        order.setPaymentStatus("PENDING");
        order.setIsCredit(true);
        if (safeRequest.getDescription() != null && !safeRequest.getDescription().isBlank()) {
            order.setDescription(appendDescription(order.getDescription(), safeRequest.getDescription().trim()));
        }

        Order saved = orderRepository.save(order);
        linkCustomersToSavedOrder(saved);
        List<Invoice> invoices = invoiceRepository.findByOrderId(saved.getId());
        if (invoices.isEmpty()) {
            generateInvoice(saved);
        } else {
            for (Invoice invoice : invoices) {
                if (isVoidStatus(invoice.getStatus()) || isVoidStatus(invoice.getDocStatus())) {
                    continue;
                }
                invoice.setCustomerId(saved.getCustomerId());
                invoice.setCreditCustomerId(saved.getCreditCustomerId());
                invoice.setIsCredit(true);
                invoice.setTotalAmount(saved.getGrandTotal());
                invoice.setAmountDue(saved.getGrandTotal());
                invoice.setStatus("UNPAID");
                invoice.setIsPaid(false);
                Invoice updatedInvoice = invoiceRepository.save(invoice);
                accountingPostingService.replaceInvoiceJournal(saved, updatedInvoice, "Credit customer attached");
            }
        }
        accountingPostingService.postSaleCogs(saved);
        handleTableStatus(saved);
        Order hydrated = hydrateOrder(saved);
        hydrated.setSkipAutoPrintKinds(safeRequest.getSkipAutoPrintKinds());
        enqueueCloudPrintJobs(hydrated);
        return hydrated;
    }

    @Transactional
    public Order moveTable(UUID id, OrderMoveTableRequest request) {
        if (request == null || request.getTableId() == null) {
            throw new IllegalArgumentException("Target table is required");
        }

        Order order = getOrder(id);
        ensureOrderCanChange(order, "move");

        UUID oldTableId = order.getTableId();
        RestaurantTable targetTable = getTenantTable(request.getTableId());
        if (oldTableId == null || !oldTableId.equals(targetTable.getId())) {
            ensureTableAvailableForOrder(targetTable);
        }

        order.setTableId(targetTable.getId());
        order.setTableNumber(targetTable.getTableNumber());
        order.setFulfillmentType("DINE_IN");

        Order saved = orderRepository.save(order);
        if (oldTableId != null && !oldTableId.equals(targetTable.getId())) {
            setTableStatus(oldTableId, "AVAILABLE", saved.getOrgId());
        }
        handleTableStatus(saved);
        publishOrderStatusUpdate(saved);
        return hydrateOrder(saved);
    }

    @Transactional
    public Order cancelOrder(UUID id, OrderCancelRequest request) {
        Order order = getOrder(id);
        ensureOrderCanChange(order, "cancel");

        String reason = request != null ? request.getReason() : null;
        order.setOrderStatus("CANCELLED");
        if (isSaleOrder(order)) {
            order.setPaymentStatus("VOID");
        }
        if (reason != null && !reason.isBlank()) {
            order.setDescription(appendDescription(order.getDescription(), "Cancel reason: " + reason.trim()));
        }

        Order saved = orderRepository.save(order);
        if (isSaleOrder(saved)) {
            voidLinkedInvoices(saved, "Order cancelled");
            voidLinkedPayments(saved, "Order cancelled");
            accountingPostingService.reverseSaleCogs(saved, "Order cancelled");
        } else {
            voidUnpaidLinkedInvoices(saved, "Order cancelled");
        }

        handleTableStatus(saved);
        publishOrderStatusUpdate(saved);
        return hydrateOrder(saved);
    }

    @Transactional
    public Invoice generateInvoice(Order order) {
        return generateInvoice(order, null, null);
    }
    @Transactional
    public Invoice generateInvoice(Order order, UUID originalInvoiceId) {
        return generateInvoice(order, originalInvoiceId, null);
    }

    @Transactional
    public Invoice generateInvoice(Order order, UUID originalInvoiceId, String requestedInvoiceNo) {
        if (!invoiceRepository.findByOrderId(order.getId()).isEmpty())
            return null;

        UUID clientId = order.getClientId();
        UUID orgId = order.getOrgId();

        // Determine invoice document type from order type
        OrderType orderType = order.getOrderType() == null ? OrderType.SALE : order.getOrderType();
        DocumentType invoiceDocType = switch (orderType) {
            case PURCHASE -> DocumentType.VENDOR_BILL;
            case EXPENSE -> DocumentType.EXPENSE_RECEIPT;
            default -> DocumentType.CUSTOMER_INVOICE;
        };

        String invNo = resolveDocumentNumber(order, invoiceDocType, requestedInvoiceNo);

        // Map to entity InvoiceType
        InvoiceType invoiceType = switch (invoiceDocType) {
            case VENDOR_BILL -> InvoiceType.VENDOR_BILL;
            case EXPENSE_RECEIPT -> InvoiceType.EXPENSE_RECEIPT;
            default -> InvoiceType.CUSTOMER_INVOICE;
        };

        LocalDateTime invoiceDate = sourceBusinessDateTime(order);
        LocalDateTime start = invoiceDate.toLocalDate().atStartOfDay();
        LocalDateTime end = invoiceDate.toLocalDate().atTime(23, 59, 59, 999999999);
        int maxNo = invoiceRepository.findMaxDailyBillNo(clientId, orgId, start, end);
        int dailyBillNo = maxNo + 1;

        Invoice invoice = Invoice.builder()
                .invoiceType(invoiceType)
                .terminalId(order.getTerminalId())
                .sourceDeviceId(order.getSourceDeviceId())
                .sourceTerminalId(order.getSourceTerminalId())
                .sourceOperationId(order.getSourceOperationId())
                .sourceOfflineId(order.getSourceOfflineId())
                .sourceLocalRef(order.getSourceLocalRef())
                .offlineCreatedAt(order.getOfflineCreatedAt())
                .syncOrigin(order.getSyncOrigin())
                .orderId(order.getId())
                .customerId(order.getCustomerId())
                .creditCustomerId(Boolean.TRUE.equals(order.getIsCredit()) ? order.getCreditCustomerId() : null)
                .vendorId(order.getVendorId())
                .invoiceNo(invNo)
                .invoiceDate(invoiceDate)
                .dailyBillNo(dailyBillNo)
                .totalAmount(order.getGrandTotal()
                        .subtract(order.getRoundOffAmount() != null ? order.getRoundOffAmount() : BigDecimal.ZERO))
                .amountDue(order.getGrandTotal()
                        .subtract(order.getRoundOffAmount() != null ? order.getRoundOffAmount() : BigDecimal.ZERO))
                .status("UNPAID")
                .isPaid(false)
                .isCredit(Boolean.TRUE.equals(order.getIsCredit()))
                .originalInvoiceId(originalInvoiceId)
                // GST Discount Engine fields (V1_110)
                .grossAmount(order.getGrossAmount())
                .totalTaxAmount(order.getTotalTaxAmount())
                .totalDiscountAmount(order.getTotalDiscountAmount())
                .taxableAmount(computeTaxableSum(order.getLines()))
                .discountSource(order.getDiscountSource())
                .discountCalculationVersion(DiscountEngineVersion.CURRENT)
                .build();

        invoice.setClientId(clientId);
        invoice.setOrgId(orgId);

        if (order.getLines() != null) {
            for (OrderLine ol : order.getLines()) {
                // Skip soft-deleted / inactive lines — they must not appear on the invoice
                if (!"Y".equalsIgnoreCase(ol.getIsactive())) continue;
                InvoiceLine il = InvoiceLine.builder()
                        .orderLineId(ol.getId())
                        .productId(ol.getProductId())
                        .variantId(ol.getVariantId())
                        .productName(ol.getProductName())
                        .categoryName(ol.getCategoryName())
                        .isPackagedGood(ol.getIsPackagedGood())
                        .quantity(ol.getQuantity())
                        .unitOfMeasure(ol.getUnitOfMeasure())
                        .unitPrice(ol.getUnitPrice())
                        .taxRate(ol.getTaxRate())
                        .taxAmount(ol.getTaxAmount())
                        .discountAmount(ol.getDiscountAmount())
                        .lineTotal(ol.getLineTotal())
                        .isactive(ol.getIsactive())
                        .createdBy(ol.getCreatedBy() != null ? ol.getCreatedBy().toString() : null)
                        .updatedBy(ol.getUpdatedBy() != null ? ol.getUpdatedBy().toString() : null)
                        // GST Enrichment snapshot (V1_110) — immutable; reuse for credit notes
                        .grossLineAmount(ol.getGrossLineAmount())
                        .unitPriceExTax(ol.getUnitPriceExTax())
                        .taxableAmount(ol.getTaxableAmount())
                        .taxType(ol.getTaxType())
                        .taxSnapshotRate(ol.getTaxSnapshotRate())
                        .taxCode(ol.getTaxCode())
                        .taxName(ol.getTaxName())
                        .manualDiscountAmount(ol.getManualDiscountAmount())
                        .manualDiscountPercent(ol.getManualDiscountPercent())
                        .allocatedOrderDiscount(ol.getAllocatedOrderDiscount())
                        .build();
                invoice.addLine(il);
            }
        }

        Invoice savedInvoice = invoiceRepository.save(invoice);
        
        // Update transient fields on the order so downstream print jobs have them
        order.setInvoiceNo(savedInvoice.getInvoiceNo());
        order.setDailyBillNo(savedInvoice.getDailyBillNo());
        
        accountingPostingService.postInvoice(order, savedInvoice);
        return savedInvoice;
    }

    @Transactional
    public void generatePayment(Order order) {
        generatePayment(order, "CASH");
    }

    @Transactional
    public void generatePayment(Order order, String paymentMethod) {
        generatePayment(order, paymentMethod, null);
    }

    @Transactional
    public void generatePayment(Order order, String paymentMethod, String requestedPaymentNo) {
        generatePayment(order, paymentMethod, requestedPaymentNo, order.getGrandTotal(), null);
    }

    @Transactional
    public void generatePayment(Order order, String paymentMethod, String requestedPaymentNo, BigDecimal amountPaid,
            String description) {
        generatePayment(order, paymentMethod, requestedPaymentNo, amountPaid, description, null, null);
    }

    @Transactional
    public void generatePayment(Order order, String paymentMethod, String requestedPaymentNo, BigDecimal amountPaid,
            String description,
            BigDecimal cashAmount, BigDecimal onlineAmount) {
        generatePayment(order, paymentMethod, requestedPaymentNo, amountPaid, description, cashAmount, onlineAmount,
                null);
    }

    /**
     * Overload that also records the round-off amount on the payment (cash
     * settlement use-case).
     */
    @Transactional
    public void generatePayment(Order order, String paymentMethod, String requestedPaymentNo, BigDecimal amountPaid,
            String description,
            BigDecimal cashAmount, BigDecimal onlineAmount,
            List<OrderSettleRequest.PaymentSplitRequest> paymentSplits,
            BigDecimal roundOffAmount) {
        generatePaymentInternal(order, paymentMethod, requestedPaymentNo, amountPaid, description, cashAmount,
                onlineAmount, paymentSplits, roundOffAmount);
    }

    @Transactional
    public void generatePayment(Order order, String paymentMethod, String requestedPaymentNo, BigDecimal amountPaid,
            String description,
            BigDecimal cashAmount, BigDecimal onlineAmount,
            List<OrderSettleRequest.PaymentSplitRequest> paymentSplits) {
        generatePaymentInternal(order, paymentMethod, requestedPaymentNo, amountPaid, description, cashAmount,
                onlineAmount, paymentSplits, null);
    }

    @Transactional
    private void generatePaymentInternal(Order order, String paymentMethod, String requestedPaymentNo,
            BigDecimal amountPaid, String description,
            BigDecimal cashAmount, BigDecimal onlineAmount,
            List<OrderSettleRequest.PaymentSplitRequest> paymentSplits,
            BigDecimal roundOffAmount) {
        if (order.getPaymentNo() != null && !order.getPaymentNo().isEmpty())
            return;
        if (!paymentRepository.findByOrderId(order.getId()).isEmpty())
            return;

        // Try to find the invoice
        List<Invoice> invoices = invoiceRepository.findByOrderId(order.getId());
        if (invoices.isEmpty()) {
            throw new BusinessException("Cannot generate payment: no active invoice found for order " + order.getId());
        }
        Invoice invoice = invoices.get(0);
        if (invoice != null) {
            accountingPostingService.postInvoice(order, invoice);
        }

        UUID clientId = order.getClientId();
        UUID orgId = order.getOrgId();

        // INBOUND = money received (Sales), OUTBOUND = money paid (Purchase/Expense)
        DocumentType paymentDocType = (order.getOrderType() == null || order.getOrderType() == OrderType.SALE)
                ? DocumentType.INBOUND_PAYMENT
                : DocumentType.OUTBOUND_PAYMENT;

        String payNo = resolveDocumentNumber(order, paymentDocType, requestedPaymentNo);

        PaymentType paymentType = (paymentDocType == DocumentType.INBOUND_PAYMENT)
                ? PaymentType.INBOUND
                : PaymentType.OUTBOUND;
        String storedPaymentMethod = normalizePaymentMethod(paymentMethod);
        if (paymentSplits != null && !paymentSplits.isEmpty()) {
            storedPaymentMethod = "MIXED";
        }

        Payment payment = Payment.builder()
                .paymentType(paymentType)
                .terminalId(order.getTerminalId())
                .sourceDeviceId(order.getSourceDeviceId())
                .sourceTerminalId(order.getSourceTerminalId())
                .sourceOperationId(order.getSourceOperationId())
                .sourceOfflineId(order.getSourceOfflineId())
                .sourceLocalRef(order.getSourceLocalRef())
                .offlineCreatedAt(order.getOfflineCreatedAt())
                .syncOrigin(order.getSyncOrigin())
                .orderId(order.getId())
                .invoiceId(invoice != null ? invoice.getId() : null)
                .customerId(order.getCustomerId())
                .creditCustomerId(Boolean.TRUE.equals(order.getIsCredit()) ? order.getCreditCustomerId() : null)
                .paymentMethod(storedPaymentMethod)
                .paymentDate(sourceBusinessDateTime(order))
                .amountPaid(moneyValue(amountPaid))
                .referenceNo(payNo)
                .description(description)
                // GST round-off: round_off_amount lives on Payment ONLY
                // Invariant: amount_paid = invoice_total + round_off_amount
                .invoiceTotal(
                        invoice != null ? moneyValue(invoice.getTotalAmount()) : moneyValue(order.getGrandTotal()))
                .roundOffAmount(
                        roundOffAmount != null ? roundOffAmount.setScale(2, RoundingMode.HALF_UP) : 
                        (order.getRoundOffAmount() != null ? order.getRoundOffAmount().setScale(2, RoundingMode.HALF_UP) : BigDecimal.ZERO))
                .build();

        payment.setClientId(clientId);
        payment.setOrgId(orgId);

        Payment savedPayment = paymentRepository.save(payment);
        savePaymentSplits(savedPayment, storedPaymentMethod, amountPaid, cashAmount, onlineAmount, paymentSplits);

        if (invoice != null) {
            PaymentAllocation allocation = PaymentAllocation.builder()
                    .paymentId(savedPayment.getId())
                    .invoiceId(invoice.getId())
                    .orderId(order.getId())
                    .creditCustomerId(order.getCreditCustomerId())
                    .allocatedAmount(moneyValue(amountPaid))
                    .allocationDate(savedPayment.getPaymentDate())
                    .status("POSTED")
                    .notes("Checkout payment allocation")
                    .build();
            allocation.setClientId(savedPayment.getClientId());
            allocation.setOrgId(savedPayment.getOrgId());
            paymentAllocationRepository.save(allocation);
        }

        // Update Invoice status if it exists
        if (invoice != null) {
            BigDecimal due = moneyValue(invoice.getTotalAmount()).subtract(moneyValue(amountPaid));
            if (due.compareTo(BigDecimal.ZERO) <= 0) {
                invoice.setStatus("PAID");
                invoice.setIsPaid(true);
                invoice.setAmountDue(BigDecimal.ZERO);
            } else {
                invoice.setStatus("PARTIAL");
                invoice.setIsPaid(false);
                invoice.setAmountDue(due);
            }
            invoiceRepository.save(invoice);
        }
        accountingPostingService.postPayment(order, savedPayment);
    }

    private void savePaymentSplits(Payment payment, String paymentMethod, BigDecimal amountPaid, BigDecimal cashAmount,
            BigDecimal onlineAmount,
            List<OrderSettleRequest.PaymentSplitRequest> requestedSplits) {
        BigDecimal totalPaid = moneyValue(amountPaid);
        if (totalPaid.compareTo(BigDecimal.ZERO) <= 0) {
            return;
        }
        String normalizedMethod = normalizePaymentMethod(paymentMethod);
        List<PaymentSplit> splits = new ArrayList<>();
        if (requestedSplits != null && !requestedSplits.isEmpty()) {
            BigDecimal splitTotal = BigDecimal.ZERO;
            for (OrderSettleRequest.PaymentSplitRequest requestedSplit : requestedSplits) {
                if (requestedSplit == null) {
                    throw new BusinessException("Payment split row is invalid");
                }
                String splitMethod = normalizePaymentSplitMethod(requestedSplit.getPaymentMethod());
                BigDecimal splitAmount = moneyValue(requestedSplit.getAmount());
                if (splitAmount.compareTo(BigDecimal.ZERO) <= 0) {
                    throw new BusinessException("Payment split amount must be greater than zero");
                }
                splitTotal = splitTotal.add(splitAmount);
                splits.add(buildPaymentSplit(payment, splitMethod, splitAmount, requestedSplit.getReferenceNo()));
            }
            ensureSplitTotalMatches(totalPaid, splitTotal);
        } else if ("MIXED".equalsIgnoreCase(normalizedMethod)) {
            BigDecimal cash = moneyValue(cashAmount);
            BigDecimal online = moneyValue(onlineAmount);
            BigDecimal splitTotal = BigDecimal.ZERO;
            if (cash.compareTo(BigDecimal.ZERO) > 0) {
                splits.add(buildPaymentSplit(payment, "CASH", cash));
                splitTotal = splitTotal.add(cash);
            }
            if (online.compareTo(BigDecimal.ZERO) > 0) {
                splits.add(buildPaymentSplit(payment, "ONLINE", online));
                splitTotal = splitTotal.add(online);
            }
            if (splits.isEmpty()) {
                throw new BusinessException("Mixed payment requires split amounts");
            }
            ensureSplitTotalMatches(totalPaid, splitTotal);
        }
        if (splits.isEmpty()) {
            splits.add(buildPaymentSplit(payment, normalizePaymentSplitMethod(normalizedMethod), totalPaid));
        }
        paymentSplitRepository.saveAll(splits);
    }

    private PaymentSplit buildPaymentSplit(Payment payment, String method, BigDecimal amount) {
        return buildPaymentSplit(payment, method, amount, payment.getReferenceNo());
    }

    private PaymentSplit buildPaymentSplit(Payment payment, String method, BigDecimal amount, String referenceNo) {
        PaymentSplit split = PaymentSplit.builder()
                .paymentId(payment.getId())
                .paymentMethod(method)
                .amount(moneyValue(amount))
                .referenceNo(
                        referenceNo == null || referenceNo.isBlank() ? payment.getReferenceNo() : referenceNo.trim())
                .build();
        split.setClientId(payment.getClientId());
        split.setOrgId(payment.getOrgId());
        return split;
    }

    private void ensureSplitTotalMatches(BigDecimal totalPaid, BigDecimal splitTotal) {
        if (moneyValue(splitTotal).compareTo(totalPaid) != 0) {
            throw new BusinessException("Payment split total must equal amount paid");
        }
    }

    private void handleTableStatus(Order order) {
        if (order.getTableId() == null)
            return;

        boolean shouldRelease = "COMPLETED".equalsIgnoreCase(order.getOrderStatus()) ||
                "CANCELLED".equalsIgnoreCase(order.getOrderStatus()) ||
                "PAID".equalsIgnoreCase(order.getPaymentStatus());

        String nextStatus = shouldRelease
                ? "AVAILABLE"
                : "BILLED".equalsIgnoreCase(order.getOrderStatus()) ? "BILLED" : "OCCUPIED";

        findTenantTableForOrder(order.getTableId(), order.getOrgId()).ifPresent(table -> {
            if (!nextStatus.equalsIgnoreCase(String.valueOf(table.getStatus()))) {
                table.setStatus(nextStatus);
                tableRepository.save(table);
            }
        });
    }

    private RestaurantTable getTenantTable(UUID tableId) {
        UUID tenantId = TenantContext.getCurrentTenant();
        UUID orgId = branchContext.getReadOrgId(null);
        if (orgId == null) {
            return tableRepository.findByIdAndClientId(tableId, tenantId)
                    .orElseThrow(() -> new ResourceNotFoundException("Target table not found"));
        }
        return tableRepository.findByIdAndClientIdAndOrgId(tableId, tenantId, orgId)
                .orElseThrow(() -> new ResourceNotFoundException("Target table not found"));
    }

    private Optional<RestaurantTable> findTenantTableForOrder(UUID tableId, UUID orgId) {
        UUID tenantId = TenantContext.getCurrentTenant();
        if (orgId == null) {
            return tableRepository.findByIdAndClientId(tableId, tenantId);
        }
        return tableRepository.findByIdAndClientIdAndOrgId(tableId, tenantId, orgId);
    }

    private void setTableStatus(UUID tableId, String status, UUID orgId) {
        findTenantTableForOrder(tableId, orgId).ifPresent(table -> {
            table.setStatus(status);
            tableRepository.save(table);
        });
    }

    private void ensureOrderCanChange(Order order, String action) {
        if (order == null) {
            throw new ResourceNotFoundException("Order not found");
        }
        String status = String.valueOf(order.getOrderStatus());
        if ("CANCELLED".equalsIgnoreCase(status) || "VOID".equalsIgnoreCase(status)) {
            throw new IllegalStateException("Cannot " + action + " a " + status.toLowerCase() + " order");
        }
        if (!"settle".equalsIgnoreCase(action)
                && !"cancel".equalsIgnoreCase(action)
                && order.getOrderType() != OrderType.PURCHASE
                && ("COMPLETED".equalsIgnoreCase(status) || "PAID".equalsIgnoreCase(order.getPaymentStatus()))) {
            throw new IllegalStateException("Cannot " + action + " a completed order");
        }
    }

    private void voidLinkedInvoices(Order order, String reason) {
        invoiceRepository.findByOrderId(order.getId()).forEach(invoice -> {
            if (!isVoidStatus(invoice.getStatus()) && !isVoidStatus(invoice.getDocStatus())) {
                accountingPostingService.reverseInvoice(invoice, reason);
            }
            invoice.setStatus("VOID");
            invoice.setDocStatus("VOIDED");
            invoice.setIsPaid(false);
            invoice.setAmountDue(BigDecimal.ZERO);
            invoiceRepository.save(invoice);
        });
    }

    private void voidUnpaidLinkedInvoices(Order order, String reason) {
        invoiceRepository.findByOrderId(order.getId()).forEach(invoice -> {
            if (!Boolean.TRUE.equals(invoice.getIsPaid())) {
                if (!isVoidStatus(invoice.getStatus()) && !isVoidStatus(invoice.getDocStatus())) {
                    accountingPostingService.reverseInvoice(invoice, reason);
                }
                invoice.setStatus("VOID");
                invoice.setDocStatus("VOIDED");
                invoice.setIsPaid(false);
                invoice.setAmountDue(BigDecimal.ZERO);
                invoiceRepository.save(invoice);
            }
        });
    }

    private void voidLinkedPayments(Order order, String reason) {
        paymentRepository.findByOrderId(order.getId()).forEach(payment -> {
            if (isActivePayment(payment)) {
                accountingPostingService.reversePayment(payment, reason);
            }
            payment.setDocStatus("VOIDED");
            payment.setIsactive("N");
            paymentRepository.save(payment);
        });
    }

    private boolean isActivePayment(Payment payment) {
        return payment != null
                && !"N".equalsIgnoreCase(payment.getIsactive())
                && !isVoidStatus(payment.getDocStatus());
    }

    private boolean isSaleOrder(Order order) {
        return order != null && (order.getOrderType() == null || order.getOrderType() == OrderType.SALE);
    }

    private boolean isVoidStatus(String status) {
        return "VOID".equalsIgnoreCase(status) || "VOIDED".equalsIgnoreCase(status);
    }

    private String normalizePaymentMethod(String paymentMethod) {
        if (paymentMethod == null || paymentMethod.isBlank()) {
            return "CASH";
        }
        return paymentMethod.trim().toUpperCase();
    }

    private String normalizePaymentSplitMethod(String paymentMethod) {
        if (paymentMethod == null || paymentMethod.isBlank()) {
            return "CASH";
        }
        return paymentMethod.trim().toUpperCase();
    }

    private boolean hasExplicitPaymentSplits(OrderSettleRequest request) {
        return request != null && request.getPaymentSplits() != null && !request.getPaymentSplits().isEmpty();
    }

    private boolean isCompletedCreditSale(Order order) {
        return isSaleOrder(order)
                && Boolean.TRUE.equals(order.getIsCredit())
                && "COMPLETED".equalsIgnoreCase(order.getOrderStatus())
                && !"PAID".equalsIgnoreCase(order.getPaymentStatus());
    }

    private BigDecimal moneyValue(BigDecimal value) {
        return (value == null ? BigDecimal.ZERO : value).setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * Sums the {@code taxableAmount} of all order lines.
     * Used to populate {@code invoices.taxable_amount} at invoice generation time.
     */
    private BigDecimal computeTaxableSum(List<OrderLine> lines) {
        if (lines == null)
            return BigDecimal.ZERO;
        return lines.stream()
                .filter(l -> l.getTaxableAmount() != null)
                .map(OrderLine::getTaxableAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * Validates GST-related business rules before saving an order.
     * Throws {@link BusinessException} on violation.
     */
    private void validateGstFields(Order order) {
        if (order.getGrossAmount() != null && order.getGrossAmount().compareTo(BigDecimal.ZERO) < 0) {
            throw new BusinessException("gross_amount must be >= 0");
        }
        BigDecimal totalDiscount = moneyValue(order.getTotalDiscountAmount());
        BigDecimal gross = moneyValue(order.getGrossAmount());
        if (gross.compareTo(BigDecimal.ZERO) > 0 && totalDiscount.compareTo(gross) > 0) {
            throw new BusinessException("total_discount_amount must not exceed gross_amount");
        }
        if (order.getLines() != null) {
            for (OrderLine line : order.getLines()) {
                BigDecimal lineGross = moneyValue(line.getGrossLineAmount());
                BigDecimal lineAlloc = moneyValue(line.getAllocatedOrderDiscount());
                if (lineAlloc.compareTo(BigDecimal.ZERO) < 0) {
                    throw new BusinessException(
                            "allocated_order_discount must be >= 0 on line: " + line.getProductName());
                }
                if (lineGross.compareTo(BigDecimal.ZERO) > 0 && lineAlloc.compareTo(lineGross) > 0) {
                    throw new BusinessException(
                            "allocated_order_discount exceeds gross_line_amount on line: " + line.getProductName());
                }
            }
        }
    }

    private BigDecimal calculateUnroundedLinesTotal(Order order) {
        if (order.getLines() == null || order.getLines().isEmpty()) {
            return BigDecimal.ZERO;
        }
        return order.getLines().stream()
                .filter(OrderLine::isActive)
                .map(line -> line.getLineTotal() != null ? line.getLineTotal() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /**
     * Enforces the payment round-off invariant:
     * {@code amount_paid = invoice_total + round_off_amount}
     *
     * A tolerance of ±0.05 is allowed to accommodate floating-point edge cases
     * without being too strict. Throws {@link BusinessException} on violation.
     */
    private void validatePaymentInvariant(BigDecimal invoiceTotal, BigDecimal roundOff, BigDecimal amountPaid) {
        if (invoiceTotal == null || amountPaid == null)
            return;
        BigDecimal safeRoundOff = (roundOff == null ? BigDecimal.ZERO : roundOff);
        BigDecimal expected = invoiceTotal.add(safeRoundOff).setScale(2, RoundingMode.HALF_UP);
        BigDecimal actual = amountPaid.setScale(2, RoundingMode.HALF_UP);
        BigDecimal diff = expected.subtract(actual).abs();
        if (diff.compareTo(new BigDecimal("0.05")) > 0) {
            throw new BusinessException(String.format(
                    "Payment invariant violated: invoice_total(%s) + round_off(%s) = %s but amount_paid = %s",
                    invoiceTotal, safeRoundOff, expected, actual));
        }
    }

    private String buildSettlementDescription(OrderSettleRequest request, String paymentMethod) {
        List<String> parts = new java.util.ArrayList<>();
        if ("MIXED".equalsIgnoreCase(paymentMethod)) {
            if (hasExplicitPaymentSplits(request)) {
                List<String> splitParts = request.getPaymentSplits().stream()
                        .filter(Objects::nonNull)
                        .map(split -> normalizePaymentSplitMethod(split.getPaymentMethod()) + ": "
                                + moneyValue(split.getAmount()))
                        .collect(Collectors.toList());
                parts.add(String.join(", ", splitParts));
            } else {
                parts.add("Cash: " + moneyValue(request.getCashAmount()));
                parts.add("Online: " + moneyValue(request.getOnlineAmount()));
            }
        }
        if (request.getDescription() != null && !request.getDescription().isBlank()) {
            parts.add(request.getDescription().trim());
        }
        return String.join("; ", parts);
    }

    private String appendDescription(String existing, String addition) {
        if (addition == null || addition.isBlank()) {
            return existing;
        }
        if (existing == null || existing.isBlank()) {
            return addition;
        }
        return existing + "\n" + addition;
    }

    private void processInventoryForOrder(Order order) {
        if (order.getWarehouseId() == null) {
            // A warehouse is required to receive stock — skip silently if not set.
            log.warn("processInventoryForOrder: skipping order {} — no warehouseId set", order.getId());
            return;
        }

        if (order.getLines() != null) {
            for (com.restaurant.pos.order.domain.OrderLine line : order.getLines()) {
                if (line.getProductId() == null) {
                    continue; // skip lines with no product
                }
                // Use the 8-arg overload that accepts an explicit orgId from the order,
                // because TenantContext may not carry the correct branch org during nested
                // transactional calls (see InventoryService.updateStock javadoc).
                inventoryService.updateStock(
                        order.getWarehouseId(),
                        line.getProductId(),
                        line.getVariantId(),
                        line.getQuantity() != null ? line.getQuantity() : java.math.BigDecimal.ONE,
                        "PURCHASE",
                        order.getId(),
                        line.getUnitPrice() != null ? line.getUnitPrice() : java.math.BigDecimal.ZERO,
                        order.getOrgId());
            }
        }
    }

    private void publishOrderStatusUpdate(Order order) {
        if (order == null)
            return;
        
        Runnable publishTask = () -> {
            try {
                String baseOrderNo = order.getOrderNo();
                if (baseOrderNo != null && baseOrderNo.contains("_VOID_")) {
                    baseOrderNo = baseOrderNo.substring(0, baseOrderNo.indexOf("_VOID_"));
                }
                String voidPrefix = baseOrderNo + "_VOID_%";
                List<Order> revisions = orderRepository.findAllRevisionsByOrderNo(order.getClientId(), baseOrderNo,
                        voidPrefix);
                for (Order rev : revisions) {
                    OrderStatusSseController.publishStatusUpdate(rev.getId(), order.getOrderStatus());
                }
            } catch (Exception e) {
                log.error("Failed to publish order status update to all revisions", e);
                OrderStatusSseController.publishStatusUpdate(order.getId(), order.getOrderStatus());
            }
        };

        if (org.springframework.transaction.support.TransactionSynchronizationManager.isSynchronizationActive()
                && org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()) {
            org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                new org.springframework.transaction.support.TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        publishTask.run();
                    }
                }
            );
        } else {
            publishTask.run();
        }
    }

    public List<PaymentSplit> getPaymentSplits(UUID orderId) {
        return paymentRepository.findByOrderId(orderId).stream()
                .filter(p -> "Y".equalsIgnoreCase(p.getIsactive()) && !"VOID".equalsIgnoreCase(p.getDocStatus()))
                .findFirst()
                .map(p -> {
                    List<PaymentSplit> splits = paymentSplitRepository.findByPaymentIdOrderByCreatedAtAsc(p.getId());
                    if (splits.isEmpty() && "MIXED".equalsIgnoreCase(p.getPaymentMethod())) {
                        BigDecimal total = p.getAmountPaid() != null ? p.getAmountPaid() : BigDecimal.ZERO;
                        BigDecimal half = total.divide(BigDecimal.valueOf(2), 2, RoundingMode.HALF_UP);
                        BigDecimal remaining = total.subtract(half);
                        return List.of(
                            PaymentSplit.builder().paymentId(p.getId()).paymentMethod("CASH").amount(half).build(),
                            PaymentSplit.builder().paymentId(p.getId()).paymentMethod("ONLINE").amount(remaining).build()
                        );
                    }
                    return splits;
                })
                .orElse(List.of());
    }

    @Transactional(readOnly = true)
    public List<OrderPaymentDto> getOrderPayments(UUID orderId) {
        List<OrderPaymentDto> list = new ArrayList<>();

        // 1. Direct payments linked via payment.orderId
        List<Payment> directPayments = paymentRepository.findByOrderId(orderId);
        for (Payment p : directPayments) {
            if ("Y".equalsIgnoreCase(p.getIsactive()) && !"VOID".equalsIgnoreCase(p.getDocStatus()) && !"VOIDED".equalsIgnoreCase(p.getDocStatus())) {
                list.add(OrderPaymentDto.builder()
                        .paymentId(p.getId())
                        .referenceNo(p.getReferenceNo())
                        .paymentDate(p.getPaymentDate())
                        .amount(p.getAmountPaid())
                        .paymentMethod(p.getPaymentMethod())
                        .description(p.getDescription())
                        .build());
            }
        }

        // 2. Allocated payments linked via payment_allocations table
        List<PaymentAllocation> allocations = paymentAllocationRepository.findByOrderIdAndIsactive(orderId, "Y");
        for (PaymentAllocation alloc : allocations) {
            boolean alreadyAdded = list.stream().anyMatch(dto -> dto.getPaymentId().equals(alloc.getPaymentId()));
            if (!alreadyAdded) {
                paymentRepository.findById(alloc.getPaymentId()).ifPresent(p -> {
                    if ("Y".equalsIgnoreCase(p.getIsactive()) && !"VOID".equalsIgnoreCase(p.getDocStatus()) && !"VOIDED".equalsIgnoreCase(p.getDocStatus())) {
                        list.add(OrderPaymentDto.builder()
                                .paymentId(p.getId())
                                .referenceNo(p.getReferenceNo())
                                .paymentDate(alloc.getAllocationDate())
                                .amount(alloc.getAllocatedAmount())
                                .paymentMethod(p.getPaymentMethod())
                                .description(p.getDescription())
                                .build());
                    }
                });
            }
        }

        list.sort((a, b) -> {
            if (a.getPaymentDate() == null && b.getPaymentDate() == null) return 0;
            if (a.getPaymentDate() == null) return 1;
            if (b.getPaymentDate() == null) return -1;
            return b.getPaymentDate().compareTo(a.getPaymentDate());
        });

        return list;
    }

    private String resolveUserDisplayName(String uidStr) {
        if (uidStr == null || uidStr.isBlank() || "SYSTEM".equalsIgnoreCase(uidStr)) {
            return "SYSTEM";
        }
        try {
            UUID userId = UUID.fromString(uidStr);
            return userRepository.findById(userId)
                    .map(u -> u.getFirstName()
                            + (u.getLastName() != null && !u.getLastName().isBlank() ? " " + u.getLastName() : ""))
                    .orElse(uidStr);
        } catch (Exception e) {
            return uidStr;
        }
    }
}
