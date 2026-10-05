package com.restaurant.pos.qrmenu.command;

import com.restaurant.pos.accounting.service.AccountingPostingService;
import com.restaurant.pos.client.domain.Client;
import com.restaurant.pos.client.repository.ClientRepository;
import com.restaurant.pos.common.dto.ConfigurationDto;
import com.restaurant.pos.common.exception.BusinessException;
import com.restaurant.pos.common.service.SystemConfigurationService;
import com.restaurant.pos.common.tenant.TenantContext;
import com.restaurant.pos.invoice.domain.Invoice;
import com.restaurant.pos.invoice.domain.InvoiceLine;
import com.restaurant.pos.invoice.repository.InvoiceRepository;
import com.restaurant.pos.order.domain.Order;
import com.restaurant.pos.order.domain.OrderLine;
import com.restaurant.pos.order.domain.OrderType;
import com.restaurant.pos.order.domain.Payment;
import com.restaurant.pos.order.domain.TaxType;
import com.restaurant.pos.order.repository.OrderRepository;
import com.restaurant.pos.order.repository.PaymentRepository;
import com.restaurant.pos.order.service.OrderService;
import com.restaurant.pos.outbox.service.OutboxService;
import com.restaurant.pos.print.domain.PrintJobKind;
import com.restaurant.pos.print.service.PrintJobService;
import com.restaurant.pos.product.domain.Product;
import com.restaurant.pos.product.repository.ProductRepository;
import com.restaurant.pos.purchasing.domain.Currency;
import com.restaurant.pos.purchasing.domain.Customer;
import com.restaurant.pos.purchasing.repository.CurrencyRepository;
import com.restaurant.pos.purchasing.repository.CustomerRepository;
import com.restaurant.pos.qrmenu.event.QrOrderPlacedEvent;
import com.restaurant.pos.qrmenu.repository.QrOrderRepository;
import com.restaurant.pos.sequence.domain.DocumentType;
import com.restaurant.pos.sequence.service.DocumentSequenceService;
import com.restaurant.pos.subscription.domain.ModuleName;
import com.restaurant.pos.table.domain.RestaurantTable;
import com.restaurant.pos.table.repository.RestaurantTableRepository;
import com.restaurant.pos.inventory.domain.StockSnapshot;
import com.restaurant.pos.inventory.repository.StockSnapshotRepository;
import com.restaurant.pos.product.domain.ProductRecipe;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;

/**
 * QR Order Command Service — the "Write Side" of CQRS for QR Menu orders.
 * <p>
 * Handles order placement, appending items to existing table orders,
 * customer resolution, and publishes {@link QrOrderPlacedEvent} for decoupled feature listeners:
 * <ul>
 *   <li>Inventory: handled by {@code QrOrderInventoryListener} via event</li>
 *   <li>Loyalty: handled by {@code QrOrderLoyaltyListener} via event</li>
 *   <li>Printing: enqueues KOT print jobs</li>
 *   <li>Table status: marks table as OCCUPIED on order</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class QrOrderCommandService {

    private final ProductRepository productRepository;
    private final RestaurantTableRepository tableRepository;
    private final OrderRepository orderRepository;
    private final QrOrderRepository qrOrderRepository;
    private final ClientRepository clientRepository;
    private final SystemConfigurationService systemConfigurationService;
    private final CustomerRepository customerRepository;
    private final CurrencyRepository currencyRepository;
    private final PrintJobService printJobService;
    private final DocumentSequenceService documentSequenceService;
    private final OutboxService outboxService;
    private final ApplicationEventPublisher eventPublisher;
    private final com.restaurant.pos.common.context.TimezoneResolver timezoneResolver;
    private final OrderService orderService;
    private final InvoiceRepository invoiceRepository;
    private final PaymentRepository paymentRepository;
    private final AccountingPostingService accountingPostingService;
    private final StockSnapshotRepository stockSnapshotRepository;

    /**
     * Processes QR order placement.
     * Merges into active table order if exists, otherwise creates a new order.
     * Dispatches QrOrderPlacedEvent for decoupled listeners (inventory deduction & loyalty).
     */
    @Transactional
    public Map<String, Object> processOrder(
            UUID clientId, UUID orgId,
            Map<String, Object> payload) {

        TenantContext.setCurrentTenant(clientId);
        TenantContext.setCurrentOrg(orgId);
        try {
            validateSubscription(clientId, orgId);

        String tableNumber = (String) payload.getOrDefault("tableNumber", "QR");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) payload.get("items");

        String customerNote = "";
        if (payload.get("customerNote") != null && !String.valueOf(payload.get("customerNote")).isBlank()) {
            customerNote = String.valueOf(payload.get("customerNote")).trim();
        } else if (payload.get("note") != null && !String.valueOf(payload.get("note")).isBlank()) {
            customerNote = String.valueOf(payload.get("note")).trim();
        } else if (payload.get("notes") != null && !String.valueOf(payload.get("notes")).isBlank()) {
            customerNote = String.valueOf(payload.get("notes")).trim();
        } else if (payload.get("remarks") != null && !String.valueOf(payload.get("remarks")).isBlank()) {
            customerNote = String.valueOf(payload.get("remarks")).trim();
        } else if (payload.get("description") != null && !String.valueOf(payload.get("description")).isBlank()) {
            customerNote = String.valueOf(payload.get("description")).trim();
        }

        UUID defaultCurrencyId = resolveDefaultCurrencyId(clientId, orgId);

        String paymentStatus = String.valueOf(payload.getOrDefault("paymentStatus", "PENDING")).toUpperCase();
        String paymentMethod = String.valueOf(payload.getOrDefault("paymentMethod", "CASH")).toUpperCase();
        String razorpayPaymentId = (String) payload.getOrDefault("razorpayPaymentId", null);
        String razorpayOrderId = (String) payload.getOrDefault("razorpayOrderId", null);

        if (items == null || items.isEmpty()) {
            throw new BusinessException("No items in order");
        }

        // Validate stock availability before proceeding if inventory is enabled
        validateStockAvailability(clientId, orgId, items);

        String tableIdStr = (String) payload.get("tableId");
        RestaurantTable table = findTable(clientId, orgId, tableIdStr != null ? tableIdStr : tableNumber);
        if (table != null) {
            if (table.getIsactive() != null && "N".equalsIgnoreCase(table.getIsactive())) {
                throw new BusinessException("This table is currently inactive and cannot accept orders.");
            }
            String tableStatus = table.getStatus() != null ? table.getStatus().toUpperCase() : "AVAILABLE";
            if ("MAINTENANCE".equals(tableStatus) || "HOLD".equals(tableStatus)) {
                throw new BusinessException("This table is currently on hold / maintenance and cannot accept orders.");
            }
            if ("RESERVED".equals(tableStatus)) {
                throw new BusinessException("This table is currently reserved and cannot accept orders.");
            }
            if (table.getTableNumber() != null) {
                tableNumber = table.getTableNumber();
            }
        }
        UUID tableId = table != null ? table.getId() : null;

        ZoneId branchZone = timezoneResolver.resolveTimezone(clientId, orgId);
        LocalDateTime branchNow = LocalDateTime.now(branchZone);
        LocalDateTime sessionCutoff = branchNow.minusHours(16);

        // Check if an active open order exists on this table (only if table is not marked AVAILABLE)
        Order targetOrder = null;
        boolean isAppended = false;
        if (tableId != null && (table == null || !"AVAILABLE".equalsIgnoreCase(String.valueOf(table.getStatus())))) {
            List<Order> activeOrders = qrOrderRepository.findActiveOrdersByTable(clientId, orgId, tableId, tableNumber, sessionCutoff);
            if (!activeOrders.isEmpty()) {
                targetOrder = activeOrders.get(0);
                isAppended = true;
            }
        }

        // --- Tax configuration ---
        ConfigurationDto qrConfig = systemConfigurationService
                .getConfigurationForClientAndBranch(clientId, orgId);
        boolean taxEnabled = qrConfig.isTaxEnabled();
        boolean pricesIncludeTax = qrConfig.isPricesIncludeTax();
        String taxLabelGlobal = (qrConfig.getTaxLabelGlobal() != null && !qrConfig.getTaxLabelGlobal().isBlank())
                ? qrConfig.getTaxLabelGlobal()
                : "GST";
        BigDecimal defaultTaxRate = BigDecimal.ZERO;
        String defaultTaxName = taxLabelGlobal;

        if (taxEnabled && qrConfig.getTaxRates() != null && !qrConfig.getTaxRates().isEmpty()) {
            List<Object> rates = qrConfig.getTaxRates();
            String defaultTaxId = qrConfig.getTaxDefaultId();
            Map<String, Object> defaultRateMap = null;
            for (Object rObj : rates) {
                if (rObj instanceof Map) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> rMap = (Map<String, Object>) rObj;
                    if (defaultTaxId != null && defaultTaxId.equals(String.valueOf(rMap.get("id")))) {
                        defaultRateMap = rMap;
                        break;
                    }
                }
            }
            if (defaultRateMap == null && !rates.isEmpty() && rates.get(0) instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> rMap = (Map<String, Object>) rates.get(0);
                defaultRateMap = rMap;
            }
            if (defaultRateMap != null) {
                if (defaultRateMap.get("value") != null) {
                    try {
                        defaultTaxRate = new BigDecimal(String.valueOf(defaultRateMap.get("value")));
                    } catch (Exception ignored) {}
                }
                if (defaultRateMap.get("name") != null) {
                    defaultTaxName = String.valueOf(defaultRateMap.get("name"));
                }
            }
        }

        // --- Build order lines ---
        List<OrderLine> newLines = new ArrayList<>();
        BigDecimal addedTotal = BigDecimal.ZERO;
        BigDecimal addedGross = BigDecimal.ZERO;
        BigDecimal addedTaxable = BigDecimal.ZERO;
        BigDecimal addedTax = BigDecimal.ZERO;

        for (Map<String, Object> cartItem : items) {
            UUID productId = UUID.fromString((String) cartItem.get("productId"));
            int qty = ((Number) cartItem.get("quantity")).intValue();
            Optional<Product> productOpt = productRepository.findWithCategoryById(productId)
                    .filter(product -> clientId.equals(product.getClientId()))
                    .filter(product -> orgId == null || product.getOrgId() == null || new UUID(0L, 0L).equals(product.getOrgId()) || orgId.equals(product.getOrgId()))
                    .filter(Product::isActive)
                    .filter(Product::isAvailable)
                    .filter(product -> !product.isIngredient())
                    .filter(product -> product.getProductType() == null || !"INGREDIENT".equalsIgnoreCase(product.getProductType().trim()));

            if (productOpt.isEmpty()) {
                throw new BusinessException("Invalid menu item in order");
            }
            Product product = productOpt.get();
            BigDecimal price = cartItem.get("price") != null ? new BigDecimal(cartItem.get("price").toString()) : product.getPrice();
            String customName = (String) cartItem.get("name");
            String productName = (customName != null && !customName.isBlank()) ? customName : (product.getName() != null ? product.getName() : "Item");
            String categoryName = product.getCategory() != null
                    ? product.getCategory().getName()
                    : (String) cartItem.getOrDefault("category", null);

            BigDecimal itemTaxRate = (product.getTaxRate() != null && product.getTaxRate().compareTo(BigDecimal.ZERO) > 0)
                    ? product.getTaxRate()
                    : (taxEnabled ? defaultTaxRate : BigDecimal.ZERO);
            BigDecimal lineRate = itemTaxRate.divide(BigDecimal.valueOf(100), 4, RoundingMode.HALF_UP);

            BigDecimal lineGross = price.multiply(BigDecimal.valueOf(qty));
            BigDecimal lineTax;
            BigDecimal taxableAmount;
            BigDecimal finalLineTotal;

            boolean isPackaged = product.isPackagedGood();
            boolean itemPricesIncludeTax = pricesIncludeTax || isPackaged;

            if (!taxEnabled || itemTaxRate.compareTo(BigDecimal.ZERO) <= 0) {
                taxableAmount = lineGross;
                lineTax = BigDecimal.ZERO;
                finalLineTotal = lineGross;
            } else if (itemPricesIncludeTax) {
                finalLineTotal = lineGross;
                taxableAmount = lineGross.divide(BigDecimal.ONE.add(lineRate), 2, RoundingMode.HALF_UP);
                lineTax = lineGross.subtract(taxableAmount);
            } else {
                taxableAmount = lineGross;
                lineTax = lineGross.multiply(lineRate).setScale(2, RoundingMode.HALF_UP);
                finalLineTotal = lineGross.add(lineTax);
            }

            BigDecimal unitPriceExTax = itemPricesIncludeTax && lineRate.compareTo(BigDecimal.ZERO) > 0
                    ? price.divide(BigDecimal.ONE.add(lineRate), 4, RoundingMode.HALF_UP)
                    : price;

            // Extract variantId if provided
            String variantIdStr = (String) cartItem.get("variantId");
            UUID variantId = (variantIdStr != null && !variantIdStr.isBlank()) ? UUID.fromString(variantIdStr) : null;

            String itemNote = null;
            if (cartItem.get("instructions") != null && !String.valueOf(cartItem.get("instructions")).isBlank()) {
                itemNote = String.valueOf(cartItem.get("instructions")).trim();
            } else if (cartItem.get("note") != null && !String.valueOf(cartItem.get("note")).isBlank()) {
                itemNote = String.valueOf(cartItem.get("note")).trim();
            } else if (cartItem.get("notes") != null && !String.valueOf(cartItem.get("notes")).isBlank()) {
                itemNote = String.valueOf(cartItem.get("notes")).trim();
            } else if (cartItem.get("description") != null && !String.valueOf(cartItem.get("description")).isBlank()) {
                itemNote = String.valueOf(cartItem.get("description")).trim();
            }

            String lineTaxName = null;
            if (qrConfig.getTaxRates() != null && !qrConfig.getTaxRates().isEmpty()) {
                final BigDecimal finalRate = itemTaxRate;
                for (Object rObj : qrConfig.getTaxRates()) {
                    if (rObj instanceof Map) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> rMap = (Map<String, Object>) rObj;
                        if (rMap.get("value") != null) {
                            try {
                                if (new BigDecimal(String.valueOf(rMap.get("value"))).compareTo(finalRate) == 0) {
                                    lineTaxName = String.valueOf(rMap.get("name"));
                                    break;
                                }
                            } catch (Exception ignored) {}
                        }
                    }
                }
            }
            if (lineTaxName == null) {
                lineTaxName = itemTaxRate.compareTo(defaultTaxRate) == 0 ? defaultTaxName : (taxLabelGlobal + " " + itemTaxRate.stripTrailingZeros().toPlainString() + "%");
            }

            OrderLine line = OrderLine.builder()
                    .productId(productId)
                    .variantId(variantId)
                    .productName(productName)
                    .categoryName(categoryName)
                    .isPackagedGood(isPackaged)
                    .quantity(BigDecimal.valueOf(qty))
                    .unitPrice(price)
                    .unitPriceExTax(unitPriceExTax)
                    .grossLineAmount(lineGross)
                    .taxableAmount(taxableAmount)
                    .taxRate(itemTaxRate)
                    .taxAmount(lineTax)
                    .lineTotal(finalLineTotal)
                    .taxType(taxEnabled ? (itemPricesIncludeTax ? TaxType.INCLUSIVE : TaxType.EXCLUSIVE) : TaxType.NONE)
                    .taxName(lineTaxName)
                    .taxCode(product.getTaxCode())
                    .description(itemNote)
                    .createdAt(LocalDateTime.now(java.time.ZoneOffset.UTC))
                    .updatedAt(LocalDateTime.now(java.time.ZoneOffset.UTC))
                    .isactive("Y")
                    .build();

            newLines.add(line);
            addedTotal = addedTotal.add(finalLineTotal);
            addedGross = addedGross.add(lineGross);
            addedTaxable = addedTaxable.add(taxableAmount);
            addedTax = addedTax.add(lineTax);
        }

        // --- Customer resolution ---
        String customerIdStr = (String) payload.get("customerId");
        UUID customerId = (customerIdStr != null && !customerIdStr.isBlank()) ? UUID.fromString(customerIdStr) : null;
        String customerPhone = (String) payload.get("customerPhone");
        String customerName = (String) payload.get("customerName");
        String customerEmail = (String) payload.get("customerEmail");

        if (customerId == null && customerPhone != null && !customerPhone.isBlank()) {
            customerId = resolveOrCreateCustomer(clientId, orgId, customerPhone, customerName);
        }
        if (customerId == null && customerEmail != null && !customerEmail.isBlank()) {
            Optional<Customer> existingByEmail = customerRepository.findByEmailAndClientId(customerEmail.trim().toLowerCase(), clientId);
            if (existingByEmail.isPresent()) {
                Customer c = existingByEmail.get();
                customerId = c.getId();
                if ((customerName == null || customerName.isBlank()) && c.getName() != null) {
                    customerName = c.getName();
                }
            }
        }
        if (customerId != null && (customerName == null || customerName.isBlank())) {
            try {
                customerRepository.findByIdAndClientId(customerId, clientId).ifPresent(c -> {
                    if (c.getName() != null && !c.getName().isBlank()) {
                        // customerName cannot be mutated directly in lambda so we check if needed
                    }
                });
            } catch (Exception ignored) {}
        }

        String resolvedCustomerName = (customerName != null && !customerName.isBlank()) ? customerName.trim() : "Guest";
        String customerAuditUser = resolvedCustomerName + " (customer)";

        Order saved;
        if (isAppended && targetOrder != null) {
            if (targetOrder.getLines() == null || targetOrder.getLines().isEmpty()) {
                targetOrder = orderRepository.findByIdWithLines(targetOrder.getId()).orElse(targetOrder);
            }
            createRevisionSnapshot(targetOrder);

            targetOrder.setRevisionNumber((targetOrder.getRevisionNumber() != null ? targetOrder.getRevisionNumber() : 0) + 1);
            for (OrderLine newLine : newLines) {
                mergeOrAddOrderLine(targetOrder, newLine, branchNow);
            }

            BigDecimal currentTotal = targetOrder.getGrandTotal() != null ? targetOrder.getGrandTotal() : BigDecimal.ZERO;
            BigDecimal newGrandTotal = currentTotal.add(addedTotal);
            targetOrder.setGrossAmount((targetOrder.getGrossAmount() != null ? targetOrder.getGrossAmount() : BigDecimal.ZERO).add(addedGross));
            targetOrder.setTotalTaxAmount((targetOrder.getTotalTaxAmount() != null ? targetOrder.getTotalTaxAmount() : BigDecimal.ZERO).add(addedTax));
            targetOrder.setTotalAmount(newGrandTotal);
            targetOrder.setGrandTotal(newGrandTotal);
            targetOrder.setUpdatedAt(LocalDateTime.now(java.time.ZoneOffset.UTC));
            targetOrder.setUpdatedBy(customerAuditUser);

            if (targetOrder.getCreatedBy() == null || targetOrder.getCreatedBy().isBlank()) {
                targetOrder.setCreatedBy(customerAuditUser);
            }

            if (targetOrder.getCurrencyId() == null) {
                targetOrder.setCurrencyId(defaultCurrencyId);
            }

            if (customerNote != null && !customerNote.isBlank()) {
                String existingDesc = targetOrder.getDescription() != null ? targetOrder.getDescription() : "";
                String updatedNote = existingDesc.isBlank() ? customerNote : existingDesc + " | Addon: " + customerNote;
                targetOrder.setDescription(updatedNote);
                targetOrder.setRemarks(updatedNote);
            }

            if (customerId != null) {
                targetOrder.setCustomerId(customerId);
            }
            targetOrder.setCustomerName(resolvedCustomerName);
            if (customerPhone != null && !customerPhone.isBlank()) {
                targetOrder.setCustomerPhone(customerPhone);
            }

            if ("PAID".equalsIgnoreCase(paymentStatus) || (razorpayPaymentId != null && !razorpayPaymentId.isBlank())) {
                targetOrder.setPaymentStatus("PAID");
                String ref = buildPaymentReference(paymentMethod, razorpayPaymentId, razorpayOrderId);
                if (ref != null && !ref.isBlank()) {
                    String existingRef = targetOrder.getReference();
                    targetOrder.setReference((existingRef == null || existingRef.isBlank()) ? ref : existingRef + " | " + ref);
                }
            }

            saved = orderRepository.saveAndFlush(targetOrder);

            syncInvoiceAndPayment(saved, true, newLines, paymentMethod, paymentStatus, razorpayPaymentId);

            try {
                printJobService.enqueueKotEditJob(saved, newLines, null, "qr_append");
            } catch (Exception ex) {
                log.warn("Failed to enqueue KOT print job for appended QR order: {}", ex.getMessage());
            }

        } else {
            // Generate official order number using DocumentSequenceService (SALE_ORDER sequence)
            String orderNo;
            try {
                orderNo = documentSequenceService.generateNextSequenceExplicit(clientId, orgId, DocumentType.SALE_ORDER);
            } catch (Exception ex) {
                log.warn("Failed to generate official sequence for SALE_ORDER, using fallback: {}", ex.getMessage());
                orderNo = "QR-" + System.currentTimeMillis();
            }

            Order order = Order.builder()
                    .id(UUID.randomUUID())
                    .orderNo(orderNo)
                    .orderType(OrderType.SALE)
                    .orderStatus("KITCHEN")
                    .paymentStatus(("PAID".equalsIgnoreCase(paymentStatus) || (razorpayPaymentId != null && !razorpayPaymentId.isBlank())) ? "PAID" : "PENDING")
                    .orderSource("QR_MENU")
                    .fulfillmentType("DINE_IN")
                    .tableNumber(tableNumber)
                    .currencyId(defaultCurrencyId)
                    .description(customerNote.isBlank() ? null : customerNote)
                    .remarks(customerNote.isBlank() ? null : customerNote)
                    .reference(buildPaymentReference(paymentMethod, razorpayPaymentId, razorpayOrderId))
                    .orderDate(branchNow.atZone(branchZone).toInstant())
                    .isactive("Y")
                    .build();

            order.setCreatedBy(customerAuditUser);
            order.setUpdatedBy(customerAuditUser);

            if (tableId != null) {
                order.setTableId(tableId);
            }
            if (customerId != null) {
                order.setCustomerId(customerId);
            }
            order.setCustomerName(resolvedCustomerName);
            if (customerPhone != null && !customerPhone.isBlank()) {
                order.setCustomerPhone(customerPhone);
            }
            order.setClientId(clientId);
            order.setOrgId(orgId);

            for (OrderLine newLine : newLines) {
                mergeOrAddOrderLine(order, newLine, branchNow);
            }

            order.setGrossAmount(addedGross);
            order.setTotalTaxAmount(addedTax);
            order.setTotalAmount(addedTotal);
            order.setGrandTotal(addedTotal);

            saved = orderRepository.saveAndFlush(order);

            syncInvoiceAndPayment(saved, false, newLines, paymentMethod, paymentStatus, razorpayPaymentId);

            try {
                printJobService.enqueueForOrder(saved, PrintJobKind.KOT, "auto");
            } catch (Exception ex) {
                log.warn("Failed to enqueue KOT print job for new QR order: {}", ex.getMessage());
            }
        }

        linkCustomerToOrder(saved);

        // --- Table status ---
        if (table != null) {
            try {
                if (!"OCCUPIED".equals(table.getStatus())) {
                    table.setStatus("OCCUPIED");
                    tableRepository.save(table);
                }
            } catch (Exception e) {
                log.warn("Could not update table status to OCCUPIED: {}", e.getMessage());
            }
        }

        // --- Publish domain event for decoupled listeners (inventory deduction & loyalty) ---
        eventPublisher.publishEvent(new QrOrderPlacedEvent(
                this,
                saved,
                newLines,
                clientId,
                orgId,
                customerId,
                isAppended
        ));

        // --- Notification outbox event ---
        enqueueNotificationOutbox(saved);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("orderId", saved.getId());
        response.put("orderNo", saved.getOrderNo());
        response.put("invoiceNo", saved.getInvoiceNo());
        response.put("dailyBillNo", saved.getDailyBillNo());
        response.put("status", saved.getOrderStatus());
        response.put("paymentStatus", saved.getPaymentStatus());
        response.put("grandTotal", saved.getGrandTotal());
        response.put("tableNumber", saved.getTableNumber());
        response.put("isAppended", isAppended);
        response.put("isStockDeducted", saved.getIsStockDeducted());
        response.put("currencyId", saved.getCurrencyId());

        return response;
        } finally {
            TenantContext.clear();
        }
    }

    private void enqueueNotificationOutbox(Order order) {
        if (order == null || order.getId() == null) return;
        try {
            outboxService.enqueue(
                    "ORDER",
                    order.getId(),
                    "ORDER_CREATED_NOTIFICATION",
                    order.getClientId(),
                    order.getOrgId(),
                    Map.of(
                            "orderId", order.getId().toString(),
                            "orderNo", order.getOrderNo() != null ? order.getOrderNo() : "",
                            "orderStatus", order.getOrderStatus() != null ? order.getOrderStatus() : "KITCHEN",
                            "orderSource", "QR_MENU"
                    )
            );
        } catch (Exception ex) {
            log.error("Failed to enqueue outbox notification for QR order {}: {}", order.getId(), ex.getMessage());
        }
    }

    private UUID resolveOrCreateCustomer(UUID clientId, UUID orgId, String phone, String name) {
        if (phone == null || phone.isBlank()) return null;
        String sanitized = phone.trim().replaceAll("[\\s()\\-]", "");
        if (sanitized.isBlank()) return null;

        try {
            Optional<Customer> existing = customerRepository.findFirstByPhoneAndClientIdOrderByCreatedAtAsc(sanitized, clientId);
            if (existing.isPresent()) {
                Customer cust = existing.get();
                if ((cust.getName() == null || "Guest".equals(cust.getName())) && name != null && !name.isBlank()) {
                    cust.setName(name);
                    return customerRepository.save(cust).getId();
                }
                return cust.getId();
            }

            Customer newCust = Customer.builder()
                    .name(name != null && !name.isBlank() ? name : "Guest")
                    .phone(sanitized)
                    .build();
            newCust.setClientId(clientId);
            newCust.setOrgId(orgId);
            return customerRepository.save(newCust).getId();
        } catch (Exception ex) {
            log.warn("Failed to resolve or create customer from phone {}: {}", sanitized, ex.getMessage());
            return null;
        }
    }

    // ── Helper methods ──

    private RestaurantTable findTable(UUID clientId, UUID orgId, String tableIdentifier) {
        if (tableIdentifier == null || tableIdentifier.isBlank()) return null;
        try {
            UUID tid = UUID.fromString(tableIdentifier);
            return tableRepository.findById(tid).orElse(null);
        } catch (IllegalArgumentException ex) {
            List<RestaurantTable> tables;
            if (orgId != null) {
                tables = tableRepository.findByClientIdAndOrgIdOrderByDisplayOrderAscTableNumberAsc(clientId, orgId);
            } else {
                tables = tableRepository.findByClientIdOrderByDisplayOrderAscTableNumberAsc(clientId);
            }
            return tables.stream()
                    .filter(t -> tableIdentifier.equalsIgnoreCase(t.getTableNumber()) || tableIdentifier.equalsIgnoreCase(t.getName()))
                    .findFirst()
                    .orElse(null);
        }
    }

    private String buildPaymentReference(String paymentMethod, String razorpayPaymentId, String razorpayOrderId) {
        String method = (paymentMethod == null || paymentMethod.isBlank()) ? "CASH" : paymentMethod.toUpperCase();
        if ("RAZORPAY".equals(method)) {
            if (razorpayPaymentId != null && !razorpayPaymentId.isBlank()) {
                return "RAZORPAY:" + razorpayPaymentId;
            }
            if (razorpayOrderId != null && !razorpayOrderId.isBlank()) {
                return "RAZORPAY:" + razorpayOrderId;
            }
        }
        return method;
    }

    private void linkCustomerToOrder(Order order) {
        if (order.getCustomerId() == null || order.getClientId() == null || order.getId() == null) {
            return;
        }
        customerRepository.findByIdAndClientId(order.getCustomerId(), order.getClientId()).ifPresent(customer -> {
            if (customer.getOrderLinks() == null) {
                customer.setOrderLinks(new ArrayList<>());
            }
            customer.getOrderLinks().removeIf(link -> order.getId().equals(link.getOrderId()));
            customer.getOrderLinks().add(Customer.OrderLink.builder()
                    .orderId(order.getId())
                    .isPrimary(true)
                    .attachedAt(Instant.now().toString())
                    .build());
            customerRepository.save(customer);
        });
    }

    private void validateSubscription(UUID clientId, UUID orgId) {
        if (clientId == null) return;
        Client client = clientRepository.findById(clientId).orElse(null);
        if (client == null) return;

        boolean active = systemConfigurationService.isModuleActive(clientId, orgId, ModuleName.TABLE_QR);
        if (!active) {
            throw new BusinessException("Table QR ordering module is not active for this subscription");
        }
    }

    /**
     * Ensures an invoice exists and is synchronized with the latest order state.
     * Generates a sequential invoice for new orders, or appends lines and updates totals for existing orders.
     * Also synchronizes payment records if the order is marked PAID (e.g. Razorpay).
     */
    private void syncInvoiceAndPayment(
            Order saved,
            boolean isAppended,
            List<OrderLine> newLines,
            String paymentMethod,
            String paymentStatus,
            String razorpayPaymentId) {

        boolean isPaid = "PAID".equalsIgnoreCase(paymentStatus)
                || "PAID".equalsIgnoreCase(saved.getPaymentStatus())
                || (razorpayPaymentId != null && !razorpayPaymentId.isBlank());

        List<Invoice> existingInvoices = invoiceRepository.findByOrderId(saved.getId());

        List<Invoice> activeInvoices = existingInvoices.stream()
                .filter(inv -> !"VOID".equalsIgnoreCase(inv.getStatus()))
                .toList();

        if (activeInvoices.isEmpty()) {
            try {
                Integer existingDailyBillNo = existingInvoices.stream()
                        .map(Invoice::getDailyBillNo)
                        .filter(n -> n != null && n > 0)
                        .findFirst()
                        .orElse(saved.getDailyBillNo());

                if (existingDailyBillNo == null && saved.getOriginalOrderId() != null) {
                    List<Invoice> prevInvoices = invoiceRepository.findByOrderId(saved.getOriginalOrderId());
                    for (Invoice prevInv : prevInvoices) {
                        if (prevInv.getDailyBillNo() != null && prevInv.getDailyBillNo() > 0) {
                            existingDailyBillNo = prevInv.getDailyBillNo();
                            break;
                        }
                    }
                }

                Invoice generatedInvoice = orderService.generateInvoice(saved, null, null, existingDailyBillNo);
                if (generatedInvoice != null) {
                    saved.setInvoiceNo(generatedInvoice.getInvoiceNo());
                    saved.setDailyBillNo(generatedInvoice.getDailyBillNo());
                }
            } catch (Exception ex) {
                log.error("Failed to generate invoice for QR order {}: {}", saved.getOrderNo(), ex.getMessage(), ex);
            }
        } else {
            for (Invoice existingInv : activeInvoices) {
                if (existingInv.getDailyBillNo() == null || existingInv.getDailyBillNo() <= 0) {
                    if (saved.getDailyBillNo() != null && saved.getDailyBillNo() > 0) {
                        existingInv.setDailyBillNo(saved.getDailyBillNo());
                    }
                }

                existingInv.setTotalAmount(saved.getGrandTotal());
                existingInv.setGrossAmount(saved.getGrossAmount());
                existingInv.setTotalTaxAmount(saved.getTotalTaxAmount());
                existingInv.setTaxableAmount(computeTaxableSum(saved.getLines()));

                // Synchronize amount due
                if (isPaid) {
                    existingInv.setStatus("PAID");
                    existingInv.setIsPaid(true);
                    existingInv.setAmountDue(BigDecimal.ZERO);
                } else {
                    BigDecimal due = saved.getGrandTotal();
                    if (existingInv.getAmountDue() != null && existingInv.getTotalAmount() != null) {
                        BigDecimal previousPaid = existingInv.getTotalAmount().subtract(existingInv.getAmountDue()).max(BigDecimal.ZERO);
                        due = saved.getGrandTotal().subtract(previousPaid).max(BigDecimal.ZERO);
                    }
                    existingInv.setAmountDue(due);
                    if (due.compareTo(BigDecimal.ZERO) == 0) {
                        existingInv.setStatus("PAID");
                        existingInv.setIsPaid(true);
                    } else if (Boolean.TRUE.equals(existingInv.getIsPaid())) {
                        existingInv.setStatus("PARTIAL");
                        existingInv.setIsPaid(false);
                    }
                }

                // Append or merge new invoice lines for newly added items
                if (newLines != null && !newLines.isEmpty()) {
                    for (OrderLine ol : newLines) {
                        if (!"Y".equalsIgnoreCase(ol.getIsactive())) continue;
                        mergeOrAddInvoiceLine(existingInv, ol);
                    }
                }

                invoiceRepository.save(existingInv);
                saved.setInvoiceNo(existingInv.getInvoiceNo());
                saved.setDailyBillNo(existingInv.getDailyBillNo());

                try {
                    accountingPostingService.replaceInvoiceJournal(saved, existingInv,
                            "Invoice updated with newly added items from QR customer");
                } catch (Exception ex) {
                    log.warn("Accounting posting failed for updated invoice: {}", ex.getMessage());
                }
            }
        }

        // Synchronize payment records if marked PAID
        if (isPaid) {
            try {
                List<Payment> existingPayments = paymentRepository.findByOrderId(saved.getId());
                if (!existingPayments.isEmpty()) {
                    for (Payment existingPay : existingPayments) {
                        if (!"VOID".equalsIgnoreCase(existingPay.getDocStatus())) {
                            existingPay.setAmountPaid(saved.getGrandTotal());
                            existingPay.setInvoiceTotal(saved.getGrandTotal());
                            existingPay.setRoundOffAmount(saved.getRoundOffAmount());
                            paymentRepository.save(existingPay);
                            try {
                                accountingPostingService.reversePayment(existingPay,
                                        "Payment amount corrected after QR items added");
                                accountingPostingService.postPayment(saved, existingPay);
                            } catch (Exception ex) {
                                log.warn("Accounting posting failed for corrected payment: {}", ex.getMessage());
                            }
                        }
                    }
                } else {
                    orderService.generatePayment(saved, (paymentMethod != null && !paymentMethod.isBlank()) ? paymentMethod : "ONLINE");
                }
            } catch (Exception ex) {
                log.error("Failed to generate/update payment record for paid QR order {}: {}", saved.getOrderNo(), ex.getMessage(), ex);
            }
        }
    }

    private void mergeOrAddOrderLine(
            Order targetOrder,
            OrderLine newLine,
            LocalDateTime branchNow) {
        OrderLine existingLine = null;
        if (targetOrder.getLines() != null) {
            existingLine = targetOrder.getLines().stream()
                    .filter(ol -> (ol.getIsactive() == null || "Y".equalsIgnoreCase(ol.getIsactive()))
                            && Objects.equals(ol.getProductId(), newLine.getProductId())
                            && Objects.equals(ol.getVariantId(), newLine.getVariantId()))
                    .findFirst()
                    .orElse(null);
        }

        if (existingLine != null) {
            existingLine.setQuantity(
                    (existingLine.getQuantity() != null ? existingLine.getQuantity() : BigDecimal.ZERO)
                            .add(newLine.getQuantity() != null ? newLine.getQuantity() : BigDecimal.ZERO));
            existingLine.setGrossLineAmount(
                    (existingLine.getGrossLineAmount() != null ? existingLine.getGrossLineAmount() : BigDecimal.ZERO)
                            .add(newLine.getGrossLineAmount() != null ? newLine.getGrossLineAmount() : BigDecimal.ZERO));
            existingLine.setTaxableAmount(
                    (existingLine.getTaxableAmount() != null ? existingLine.getTaxableAmount() : BigDecimal.ZERO)
                            .add(newLine.getTaxableAmount() != null ? newLine.getTaxableAmount() : BigDecimal.ZERO));
            existingLine.setTaxAmount(
                    (existingLine.getTaxAmount() != null ? existingLine.getTaxAmount() : BigDecimal.ZERO)
                            .add(newLine.getTaxAmount() != null ? newLine.getTaxAmount() : BigDecimal.ZERO));
            existingLine.setLineTotal(
                    (existingLine.getLineTotal() != null ? existingLine.getLineTotal() : BigDecimal.ZERO)
                            .add(newLine.getLineTotal() != null ? newLine.getLineTotal() : BigDecimal.ZERO));

            if (newLine.getDiscountAmount() != null) {
                existingLine.setDiscountAmount(
                        (existingLine.getDiscountAmount() != null ? existingLine.getDiscountAmount() : BigDecimal.ZERO)
                                .add(newLine.getDiscountAmount()));
            }

            if (newLine.getDescription() != null && !newLine.getDescription().isBlank()) {
                String curDesc = existingLine.getDescription();
                if (curDesc == null || curDesc.isBlank()) {
                    existingLine.setDescription(newLine.getDescription().trim());
                } else if (!curDesc.contains(newLine.getDescription().trim())) {
                    existingLine.setDescription(curDesc + " | " + newLine.getDescription().trim());
                }
            }

            existingLine.setUpdatedAt(LocalDateTime.now(java.time.ZoneOffset.UTC));
        } else {
            targetOrder.addLine(newLine);
        }
    }

    private void mergeOrAddInvoiceLine(
            Invoice existingInv,
            OrderLine ol) {
        InvoiceLine existingInvLine = null;
        if (existingInv.getLines() != null) {
            existingInvLine = existingInv.getLines().stream()
                    .filter(il -> (il.getIsactive() == null || "Y".equalsIgnoreCase(il.getIsactive()))
                            && Objects.equals(il.getProductId(), ol.getProductId())
                            && Objects.equals(il.getVariantId(), ol.getVariantId()))
                    .findFirst()
                    .orElse(null);
        }

        if (existingInvLine != null) {
            existingInvLine.setQuantity(
                    (existingInvLine.getQuantity() != null ? existingInvLine.getQuantity() : BigDecimal.ZERO)
                            .add(ol.getQuantity() != null ? ol.getQuantity() : BigDecimal.ZERO));
            existingInvLine.setGrossLineAmount(
                    (existingInvLine.getGrossLineAmount() != null ? existingInvLine.getGrossLineAmount() : BigDecimal.ZERO)
                            .add(ol.getGrossLineAmount() != null ? ol.getGrossLineAmount() : BigDecimal.ZERO));
            existingInvLine.setTaxableAmount(
                    (existingInvLine.getTaxableAmount() != null ? existingInvLine.getTaxableAmount() : BigDecimal.ZERO)
                            .add(ol.getTaxableAmount() != null ? ol.getTaxableAmount() : BigDecimal.ZERO));
            existingInvLine.setTaxAmount(
                    (existingInvLine.getTaxAmount() != null ? existingInvLine.getTaxAmount() : BigDecimal.ZERO)
                            .add(ol.getTaxAmount() != null ? ol.getTaxAmount() : BigDecimal.ZERO));
            existingInvLine.setLineTotal(
                    (existingInvLine.getLineTotal() != null ? existingInvLine.getLineTotal() : BigDecimal.ZERO)
                            .add(ol.getLineTotal() != null ? ol.getLineTotal() : BigDecimal.ZERO));
            if (ol.getDiscountAmount() != null) {
                existingInvLine.setDiscountAmount(
                        (existingInvLine.getDiscountAmount() != null ? existingInvLine.getDiscountAmount() : BigDecimal.ZERO)
                                .add(ol.getDiscountAmount()));
            }
            if (ol.getUpdatedBy() != null) {
                existingInvLine.setUpdatedBy(ol.getUpdatedBy().toString());
            }
        } else {
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
            existingInv.addLine(il);
        }
    }

    private BigDecimal computeTaxableSum(List<OrderLine> lines) {
        if (lines == null) return BigDecimal.ZERO;
        return lines.stream()
                .filter(l -> !"N".equalsIgnoreCase(l.getIsactive()) && l.getTaxableAmount() != null)
                .map(OrderLine::getTaxableAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(2, RoundingMode.HALF_UP);
    }

    private UUID resolveDefaultCurrencyId(UUID clientId, UUID orgId) {
        if (clientId == null) return null;
        try {
            if (orgId != null) {
                Optional<Currency> defaultCurrency = currencyRepository.findByClientIdAndOrgIdAndIsDefaultTrue(clientId, orgId)
                        .stream().findFirst();
                if (defaultCurrency.isPresent()) {
                    return defaultCurrency.get().getId();
                }
            }
            return currencyRepository.findByClientIdAndIsDefaultTrue(clientId)
                    .stream().findFirst()
                    .map(Currency::getId)
                    .orElse(null);
        } catch (Exception ex) {
            log.warn("Failed to resolve default currency for clientId={} orgId={}: {}", clientId, orgId, ex.getMessage());
            return null;
        }
    }

    private void createRevisionSnapshot(Order targetOrder) {
        if (targetOrder == null) {
            return;
        }
        try {
            int currentRev = targetOrder.getRevisionNumber() != null ? targetOrder.getRevisionNumber() : 0;
            String baseOrderNo = targetOrder.getOrderNo();
            if (baseOrderNo != null && baseOrderNo.contains("_VOID_")) {
                baseOrderNo = baseOrderNo.substring(0, baseOrderNo.indexOf("_VOID_"));
            }
            String snapshotOrderNo = baseOrderNo + "_VOID_" + currentRev;

            if (targetOrder.getClientId() != null) {
                Optional<Order> existingSnapshot = orderRepository.findByOrderNoAndClientId(snapshotOrderNo, targetOrder.getClientId());
                if (existingSnapshot.isPresent()) {
                    log.info("Revision snapshot {} already exists, skipping creation", snapshotOrderNo);
                    return;
                }
            }

            Order snapshot = new Order();
            snapshot.setId(UUID.randomUUID());
            snapshot.setOrderNo(snapshotOrderNo);
            snapshot.setOrderType(targetOrder.getOrderType() != null ? targetOrder.getOrderType() : OrderType.SALE);
            snapshot.setOrderStatus("VOID");
            snapshot.setDocStatus("VOID");
            snapshot.setIsactive("N");
            snapshot.setPaymentStatus(targetOrder.getPaymentStatus());
            snapshot.setOrderSource(targetOrder.getOrderSource());
            snapshot.setTerminalId(targetOrder.getTerminalId());
            snapshot.setClientId(targetOrder.getClientId());
            snapshot.setOrgId(targetOrder.getOrgId());
            snapshot.setOrderDate(targetOrder.getOrderDate());
            snapshot.setCurrencyId(targetOrder.getCurrencyId());
            snapshot.setCustomerId(targetOrder.getCustomerId());
            snapshot.setCustomerName(targetOrder.getCustomerName());
            snapshot.setCustomerPhone(targetOrder.getCustomerPhone());
            snapshot.setGrossAmount(targetOrder.getGrossAmount());
            snapshot.setTotalTaxAmount(targetOrder.getTotalTaxAmount());
            snapshot.setTotalDiscountAmount(targetOrder.getTotalDiscountAmount());
            snapshot.setTotalAmount(targetOrder.getTotalAmount());
            snapshot.setGrandTotal(targetOrder.getGrandTotal());
            snapshot.setDescription(targetOrder.getDescription());
            snapshot.setRemarks(targetOrder.getRemarks());
            snapshot.setReference(targetOrder.getReference());
            snapshot.setFulfillmentType(targetOrder.getFulfillmentType());
            snapshot.setIsStockDeducted(targetOrder.getIsStockDeducted());
            snapshot.setRevisionNumber(currentRev);
            snapshot.setOriginalOrderId(targetOrder.getOriginalOrderId() != null ? targetOrder.getOriginalOrderId() : targetOrder.getId());
            snapshot.setCreatedBy(targetOrder.getCreatedBy());
            snapshot.setUpdatedBy(targetOrder.getUpdatedBy());
            snapshot.setCreatedAt(targetOrder.getCreatedAt() != null ? targetOrder.getCreatedAt() : LocalDateTime.now(java.time.ZoneOffset.UTC));
            snapshot.setUpdatedAt(LocalDateTime.now(java.time.ZoneOffset.UTC));

            if (targetOrder.getLines() != null) {
                for (OrderLine line : targetOrder.getLines()) {
                    if (line == null) continue;
                    OrderLine snapLine = OrderLine.builder()
                            .id(UUID.randomUUID())
                            .order(snapshot)
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
                            .grossLineAmount(line.getGrossLineAmount())
                            .unitPriceExTax(line.getUnitPriceExTax())
                            .taxableAmount(line.getTaxableAmount())
                            .taxType(line.getTaxType())
                            .taxName(line.getTaxName())
                            .taxCode(line.getTaxCode())
                            .description(line.getDescription())
                            .isactive("Y")
                            .createdAt(line.getCreatedAt())
                            .updatedAt(line.getUpdatedAt())
                            .build();
                    snapshot.addLine(snapLine);
                }
            }

            orderRepository.saveAndFlush(snapshot);
            log.info("Saved order revision snapshot {} for base order {}", snapshotOrderNo, baseOrderNo);
        } catch (Exception ex) {
            log.warn("Failed to create revision snapshot for order {}: {}", targetOrder.getOrderNo(), ex.getMessage());
        }
    }

    /**
     * Validates that requested items and ingredients do not exceed available stock
     * when the inventory tracking module is enabled for the client and branch.
     */
    private void validateStockAvailability(UUID clientId, UUID orgId, List<Map<String, Object>> items) {
        if (items == null || items.isEmpty()) return;

        ConfigurationDto config = systemConfigurationService.getConfigurationForClientAndBranch(clientId, orgId);
        if (config == null || !config.isInventoryEnabled()) {
            return;
        }

        String nonStockPolicy = config.getNonStockSalesPolicy() != null
                ? config.getNonStockSalesPolicy().trim().toUpperCase()
                : "NONE";
        boolean isBlockOrWarn = "BLOCK".equalsIgnoreCase(nonStockPolicy)
                || "WARNING".equalsIgnoreCase(nonStockPolicy)
                || "WARN".equalsIgnoreCase(nonStockPolicy);
        if (!isBlockOrWarn) {
            return;
        }

        Map<String, BigDecimal> stockMap = new HashMap<>();
        try {
            List<StockSnapshot> snapshots = stockSnapshotRepository.findByClientIdAndOrgIdOrGlobal(clientId, orgId);
            if (snapshots == null || snapshots.isEmpty()) {
                snapshots = stockSnapshotRepository.findByClientId(clientId);
            }
            if (snapshots != null) {
                for (StockSnapshot snap : snapshots) {
                    if (snap == null || snap.getProductId() == null) continue;
                    BigDecimal qty = snap.getCurrentQuantity() != null ? snap.getCurrentQuantity() : BigDecimal.ZERO;
                    String productKey = snap.getProductId().toString().toLowerCase();
                    if (snap.getVariantId() != null) {
                        String variantKey = productKey + "_" + snap.getVariantId().toString().toLowerCase();
                        stockMap.merge(variantKey, qty, BigDecimal::add);
                    }
                    stockMap.merge(productKey, qty, BigDecimal::add);
                }
            }
        } catch (Exception e) {
            log.warn("Failed to load stock snapshots for QR order validation: {}", e.getMessage());
            return;
        }

        Map<String, BigDecimal> requestedDirectStock = new HashMap<>();
        Map<String, BigDecimal> requestedIngredientStock = new HashMap<>();

        for (Map<String, Object> cartItem : items) {
            if (cartItem.get("productId") == null) continue;
            UUID productId;
            try {
                productId = UUID.fromString(String.valueOf(cartItem.get("productId")));
            } catch (Exception e) {
                continue;
            }

            int qty = 1;
            if (cartItem.get("quantity") instanceof Number) {
                qty = ((Number) cartItem.get("quantity")).intValue();
            } else if (cartItem.get("quantity") != null) {
                try {
                    qty = Integer.parseInt(String.valueOf(cartItem.get("quantity")));
                } catch (Exception ignored) {}
            }
            if (qty <= 0) continue;

            String variantIdStr = (String) cartItem.get("variantId");
            UUID variantId = (variantIdStr != null && !variantIdStr.isBlank()) ? UUID.fromString(variantIdStr) : null;

            Product product = productRepository.findWithCategoryById(productId).orElse(null);
            if (product == null) continue;

            String customName = (String) cartItem.get("name");
            String productName = (customName != null && !customName.isBlank()) ? customName : (product.getName() != null ? product.getName() : "Item");

            List<ProductRecipe> activeRecipes = Collections.emptyList();
            try {
                if (product.getRecipeLines() != null) {
                    activeRecipes = product.getRecipeLines().stream()
                            .filter(ProductRecipe::isActive)
                            .filter(r -> r.getIngredient() != null && r.getIngredient().getId() != null
                                    && r.getQuantity() != null && r.getQuantity().signum() > 0)
                            .collect(java.util.stream.Collectors.toList());
                }
            } catch (Exception ignored) {}

            if (!activeRecipes.isEmpty()) {
                List<ProductRecipe> applicableRecipes = activeRecipes.stream()
                        .filter(r -> r.getVariantOption() == null || (variantId != null && variantId.equals(r.getVariantOption().getId())))
                        .collect(java.util.stream.Collectors.toList());

                for (ProductRecipe r : applicableRecipes) {
                    UUID ingId = r.getIngredient().getId();
                    String ingKey = ingId.toString().toLowerCase();
                    BigDecimal needed = r.getQuantity().multiply(BigDecimal.valueOf(qty));
                    BigDecimal cumNeeded = requestedIngredientStock.merge(ingKey, needed, BigDecimal::add);

                    BigDecimal available = stockMap.getOrDefault(ingKey, BigDecimal.ZERO);
                    if (available.compareTo(cumNeeded) < 0) {
                        BigDecimal maxPortions = available.divide(r.getQuantity(), 0, RoundingMode.FLOOR);
                        if (maxPortions.compareTo(BigDecimal.ZERO) <= 0) {
                            throw new BusinessException("'" + productName + "' is currently out of stock.");
                        } else {
                            throw new BusinessException("Only " + maxPortions.intValue() + " available for '" + productName + "' (requested: " + qty + ").");
                        }
                    }
                }
            } else {
                String pKey = productId.toString().toLowerCase();
                String directKey = (variantId != null) ? pKey + "_" + variantId.toString().toLowerCase() : pKey;

                BigDecimal available = stockMap.get(directKey);
                if (available == null && variantId != null) {
                    available = stockMap.get(pKey);
                }

                if (available != null || product.isPackagedGood()) {
                    BigDecimal availQty = available != null ? available : BigDecimal.ZERO;
                    BigDecimal cumReq = requestedDirectStock.merge(directKey, BigDecimal.valueOf(qty), BigDecimal::add);
                    if (availQty.compareTo(cumReq) < 0) {
                        if (availQty.compareTo(BigDecimal.ZERO) <= 0) {
                            throw new BusinessException("'" + productName + "' is currently out of stock.");
                        } else {
                            throw new BusinessException("Only " + availQty.intValue() + " available for '" + productName + "' (requested: " + cumReq.intValue() + ").");
                        }
                    }
                }
            }
        }
    }
}
