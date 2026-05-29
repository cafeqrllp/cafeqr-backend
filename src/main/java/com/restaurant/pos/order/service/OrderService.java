package com.restaurant.pos.order.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.restaurant.pos.accounting.service.AccountingPostingService;
import com.restaurant.pos.common.dto.ConfigurationDto;
import com.restaurant.pos.common.exception.ResourceNotFoundException;
import com.restaurant.pos.common.exception.BusinessException;
import com.restaurant.pos.common.service.BranchContextService;
import com.restaurant.pos.common.service.SystemConfigurationService;
import com.restaurant.pos.common.tenant.TenantContext;
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
import com.restaurant.pos.order.dto.OrderCancelRequest;
import com.restaurant.pos.order.dto.OrderCreditCompletionRequest;
import com.restaurant.pos.order.dto.OrderCustomerDto;
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
import com.restaurant.pos.product.domain.Product;
import com.restaurant.pos.product.repository.ProductRepository;
import com.restaurant.pos.sequence.domain.DocumentType;
import com.restaurant.pos.sequence.service.DocumentSequenceService;
import com.restaurant.pos.sequence.service.OfflineSequenceLeaseService;
import com.restaurant.pos.table.domain.RestaurantTable;
import com.restaurant.pos.table.repository.RestaurantTableRepository;
import com.restaurant.pos.purchasing.domain.Customer;
import com.restaurant.pos.purchasing.repository.CustomerRepository;
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
    private static final int MAX_HISTORY_PAGE_SIZE = 50;
    private static final int MAX_SYNC_ORDER_CHANGES = 200;
    private static final Duration DEFAULT_HISTORY_WINDOW = Duration.ofDays(1);
    private static final List<String> PAYMENT_METHODS = List.of("CASH", "ONLINE", "UPI", "CARD", "BANK", "CHEQUE", "MIXED");
    private static final List<String> PAYMENT_SPLIT_METHODS = List.of("CASH", "ONLINE", "UPI", "CARD", "BANK", "CHEQUE");
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Kolkata");

    private final OrderRepository orderRepository;
    private final InvoiceRepository invoiceRepository;
    private final PaymentRepository paymentRepository;
    private final PaymentSplitRepository paymentSplitRepository;
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
        if (requestedNumber != null && !requestedNumber.isBlank() && isMainOfflineSync(order)) {
            offlineSequenceLeaseService.consumeLeasedNumber(
                    documentType,
                    requestedNumber,
                    order.getSourceTerminalId() != null ? order.getSourceTerminalId() : order.getTerminalId()
            );
            return requestedNumber;
        }
        return sequenceService.generateNextSequence(documentType);
    }

    private boolean isMainOfflineSync(Order order) {
        return order != null && "MAIN_OFFLINE".equalsIgnoreCase(order.getSyncOrigin());
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
            RestaurantTable table = tableRepository.findByIdAndClientId(order.getTableId(), TenantContext.getCurrentTenant())
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
            return LocalDateTime.ofInstant(order.getOrderDate(), BUSINESS_ZONE);
        }
        if (order != null && order.getOfflineCreatedAt() != null) {
            return order.getOfflineCreatedAt();
        }
        return LocalDateTime.now();
    }

    private boolean shouldGenerateInvoice(Order order) {
        if (order == null 
                || "DRAFT".equalsIgnoreCase(order.getOrderStatus())
                || "CANCELLED".equalsIgnoreCase(order.getOrderStatus()) 
                || "VOID".equalsIgnoreCase(order.getOrderStatus())) {
            return false;
        }
        if (order.getOrderType() == OrderType.PURCHASE || order.getOrderType() == OrderType.SALE || order.getOrderType() == null) {
            return true;
        }
        return "COMPLETED".equalsIgnoreCase(order.getOrderStatus()) || "BILLED".equalsIgnoreCase(order.getOrderStatus());
    }

    private void enqueueCloudPrintJobs(Order order) {
        try {
            if (order == null || order.getOrderType() != OrderType.SALE || isMainOfflineSync(order)) {
                return;
            }
            if ("KITCHEN".equalsIgnoreCase(order.getOrderStatus()) || "CONFIRMED".equalsIgnoreCase(order.getOrderStatus())) {
                printJobService.enqueueForOrder(order, PrintJobKind.KOT, "auto");
            } else if ("COMPLETED".equalsIgnoreCase(order.getOrderStatus())) {
                printJobService.enqueueForOrder(order, PrintJobKind.BILL, "auto");
            }
        } catch (Exception ex) {
            log.warn("Unable to enqueue cloud print job for order {}", order == null ? null : order.getId(), ex);
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
                            + ". Change it to Available before placing an order."
            );
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
                .anyMatch(line -> line.getProductId() != null && (
                        line.getProductName() == null || line.getProductName().isBlank()
                                || line.getCategoryName() == null || line.getCategoryName().isBlank()
                                || line.getIsPackagedGood() == null
                ));

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
                    throw new BusinessException("Product '" + product.getName() + "' has ingredients and cannot be purchased directly.");
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
        if (order == null) return;
        if (order.getOrderType() != null && order.getOrderType() != OrderType.SALE) return;

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
        if (order == null) return;
        if (order.getOrderType() != null && order.getOrderType() != OrderType.SALE) return;
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
            boolean linkToOrder
    ) {
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

        ensureCreditLedgerEnabled();
        CreditCustomer creditCustomer = creditCustomerRepository.findByIdAndClientId(creditCustomerId, order.getClientId())
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

    private void ensureCreditLedgerEnabled() {
        ConfigurationDto config = configurationService.getConfiguration();
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
                order != null ? order.getTerminalId() : null
        );
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
                ex
        );
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
            Optional<Customer> existing = customerRepository.findFirstByPhoneAndClientIdOrderByCreatedAtAsc(phone, clientId);
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
            addCustomerSelection(selections, new CustomerSelection(null, order.getCustomerName(), order.getCustomerPhone()));
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
                new CustomerSelection(customer.getId(), customer.getName(), customer.getPhone())
        ));
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
                orderNeedle(order.getId(), true)
        ));
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
        orders.forEach(this::hydrateOrderCustomers);
        return orders;
    }

    private OrderSummaryDto toOrderSummary(Order order) {
        Order hydrated = hydrateOrderCustomers(order);
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
                .orderDate(hydrated.getOrderDate())
                .createdAt(hydrated.getCreatedAt())
                .updatedAt(hydrated.getUpdatedAt())
                .invoiceNo(hydrated.getInvoiceNo())
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
                        .unitPrice(line.getUnitPrice())
                        .taxRate(line.getTaxRate())
                        .taxAmount(line.getTaxAmount())
                        .discountAmount(line.getDiscountAmount())
                        .lineTotal(line.getLineTotal())
                        .build())
                .toList();
    }

    private List<OrderSummaryDto> mergeOrderSummaries(List<OrderSummaryDto> first, List<OrderSummaryDto> second) {
        Map<UUID, OrderSummaryDto> merged = new LinkedHashMap<>();
        first.forEach(order -> {
            if (order.getId() != null) merged.put(order.getId(), order);
        });
        second.forEach(order -> {
            if (order.getId() != null) merged.putIfAbsent(order.getId(), order);
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
                likePattern(searchTerm)
        ));
    }

    private Set<UUID> findCustomerSearchOrderIds(UUID tenantId, UUID orgId, String searchTerm) {
        if (searchTerm == null) {
            return Set.of();
        }
        return new LinkedHashSet<>(customerRepository.findLinkedOrderIdsByClientAndOrgAndCustomerSearch(
                tenantId,
                orgId,
                likePattern(searchTerm)
        ));
    }

    private Specification<Order> salesHistorySpec(
            UUID orgId,
            Instant fromDate,
            Instant toDate,
            String searchTerm,
            boolean exactDocumentSearch,
            Set<UUID> customerIds,
            Set<UUID> customerOrderIds
    ) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            UUID tenantId = TenantContext.getCurrentTenant();

            predicates.add(cb.equal(root.get("clientId"), tenantId));
            if (orgId != null) {
                predicates.add(cb.equal(root.get("orgId"), orgId));
            }
            predicates.add(cb.equal(root.get("orderType"), OrderType.SALE));
            predicates.add(cb.equal(root.get("isactive"), "Y"));
            predicates.add(cb.notEqual(root.get("orderStatus"), "VOID"));

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
                            cb.equal(cb.lower(root.get("paymentNo")), lowered)
                    ));
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
                .anyMatch(link -> Objects.equals(orderId, link.getOrderId()) && Boolean.TRUE.equals(link.getIsPrimary()));
    }

    private OrderCustomerDto toOrderCustomerDto(Customer customer, boolean primary) {
        return OrderCustomerDto.builder()
                .id(customer.getId())
                .name(customer.getName())
                .phone(customer.getPhone())
                .primary(primary)
                .build();
    }

    private record CustomerSelection(UUID id, String name, String phone) {}

    public List<Order> getOrders(String status) {
        UUID tenantId = TenantContext.getCurrentTenant();
        UUID orgId = branchContext.getReadOrgId(null);
        List<String> statuses = (status != null && !status.isEmpty()) ? Arrays.asList(status.split(",")) : null;

        List<Order> orders;
        if (orgId == null) {
            if (statuses != null) orders = orderRepository.findByClientIdAndOrderStatusInOrderByCreatedAtDesc(tenantId, statuses);
            else orders = orderRepository.findByClientIdOrderByCreatedAtDesc(tenantId);
        } else {
            if (statuses != null) orders = orderRepository.findByClientIdAndOrgIdAndOrderStatusInOrderByCreatedAtDesc(tenantId, orgId, statuses);
            else orders = orderRepository.findByClientIdAndOrgIdOrderByCreatedAtDesc(tenantId, orgId);
        }

        // Lazy generate documents for completed orders that are missing them
        orders.stream()
            .filter(o -> "COMPLETED".equalsIgnoreCase(o.getOrderStatus()) && (o.getInvoiceNo() == null || o.getInvoiceNo().isEmpty()))
            .forEach(o -> {
                try { generateInvoice(o); } catch (Exception ignored) {}
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
                ordersPage = orderRepository.findByClientIdAndOrgIdAndOrderStatusIn(tenantId, orgId, statuses, pageable);
            } else {
                ordersPage = orderRepository.findByClientIdAndOrgId(tenantId, orgId, pageable);
            }
        }

        // Lazy generate documents for completed orders that are missing them
        ordersPage.getContent().stream()
            .filter(o -> "COMPLETED".equalsIgnoreCase(o.getOrderStatus()) && (o.getInvoiceNo() == null || o.getInvoiceNo().isEmpty()))
            .forEach(o -> {
                try { generateInvoice(o); } catch (Exception ignored) {}
            });

        return ordersPage.map(this::hydrateOrder);
    }

    @Transactional(readOnly = true)
    public List<Order> getOrdersByType(OrderType orderType) {
        UUID tenantId = TenantContext.getCurrentTenant();
        UUID orgId = branchContext.getReadOrgId(null);
        if (orgId == null) {
            return hydrateOrders(orderRepository.findByClientIdAndOrderTypeOrderByCreatedAtDesc(tenantId, orderType));
        }
        return hydrateOrders(orderRepository.findByClientIdAndOrgIdAndOrderTypeOrderByCreatedAtDesc(
                tenantId, orgId, orderType));
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
    public Page<OrderSummaryDto> getSalesOrderHistory(Instant fromDate, Instant toDate, int page, int size, String searchTerm) {
        Instant effectiveTo = toDate != null ? toDate : Instant.now();
        Instant effectiveFrom = fromDate != null ? fromDate : effectiveTo.minus(DEFAULT_HISTORY_WINDOW);
        validateHistoryWindow(effectiveFrom, effectiveTo);
        String normalizedSearch = normalizeHistorySearch(searchTerm);

        Pageable pageable = PageRequest.of(
                Math.max(page, 0),
                clampPageSize(size),
                Sort.by(Sort.Order.desc("orderDate"), Sort.Order.desc("createdAt"))
        );

        UUID orgId = branchContext.getReadOrgId(null);
        if (normalizedSearch != null) {
            Page<Order> exactDocumentMatches = orderRepository.findAll(
                    salesHistorySpec(orgId, null, null, normalizedSearch, true, Set.of(), Set.of()),
                    pageable
            );
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
                salesHistorySpec(orgId, effectiveFrom, effectiveTo, normalizedSearch, false, customerIds, customerOrderIds),
                pageable
        ).map(this::toOrderSummary);
    }

    @Transactional(readOnly = true)
    public List<OrderSummaryDto> getSyncBootstrapOrders() {
        List<OrderSummaryDto> live = getLiveSalesOrders();
        Page<OrderSummaryDto> recent = getSalesOrderHistory(
                Instant.now().minus(DEFAULT_HISTORY_WINDOW),
                Instant.now(),
                0,
                MAX_HISTORY_PAGE_SIZE,
                null
        );
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
                        PageRequest.of(0, MAX_SYNC_ORDER_CHANGES)
                )
                .stream()
                .map(this::toOrderSummary)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<Order> searchOrders(com.restaurant.pos.order.dto.OrderSearchCriteria criteria) {
        UUID clientId = TenantContext.getCurrentTenant();
        UUID orgId = branchContext.getReadOrgId(null);
        
        org.springframework.data.jpa.domain.Specification<Order> spec = 
            com.restaurant.pos.order.spec.OrderSpecification.filterBy(criteria, clientId, orgId);
            
        return hydrateOrders(orderRepository.findAll(spec, org.springframework.data.domain.Sort.by(org.springframework.data.domain.Sort.Direction.DESC, "createdAt")));
    }

    @Transactional(readOnly = true)
    public Page<Order> searchOrders(com.restaurant.pos.order.dto.OrderSearchCriteria criteria, Pageable pageable) {
        UUID clientId = TenantContext.getCurrentTenant();
        UUID orgId = branchContext.getReadOrgId(null);
        
        org.springframework.data.jpa.domain.Specification<Order> spec = 
            com.restaurant.pos.order.spec.OrderSpecification.filterBy(criteria, clientId, orgId);
            
        return orderRepository.findAll(spec, pageable).map(this::hydrateOrder);
    }

    @Transactional(readOnly = true)
    public Order getOrder(UUID id) {
        UUID tenantId = TenantContext.getCurrentTenant();
        UUID orgId = branchContext.getReadOrgId(null);
        if (orgId == null) {
            return hydrateOrder(orderRepository.findByIdAndClientId(id, tenantId)
                    .orElseThrow(() -> new ResourceNotFoundException("Order not found or access denied")));
        }
        return hydrateOrder(orderRepository.findByIdAndClientIdAndOrgId(id, tenantId, orgId)
                .orElseThrow(() -> new ResourceNotFoundException("Order not found or access denied")));
    }

    @Transactional
    public Order createOrder(Order order) {
        log.info("Creating order: {} | Tenant: {} | Org: {}", order, TenantContext.getCurrentTenant(), TenantContext.getCurrentOrg());
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
            prepareCustomerFields(order);

            diagnosticPhase = "save_order";
            Order saved = orderRepository.save(order);

            diagnosticPhase = "link_customers";
            linkCustomersToSavedOrder(saved);

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
                if ("COMPLETED".equalsIgnoreCase(saved.getOrderStatus()) && "PAID".equalsIgnoreCase(saved.getPaymentStatus())) {
                    String salePaymentMethod = saved.getReference() != null ? saved.getReference() : "CASH";
                    generatePayment(saved, salePaymentMethod, requestedPaymentNo);
                    accountingPostingService.postSaleCogs(saved);
                } else if (isCompletedCreditSale(saved)) {
                    accountingPostingService.postSaleCogs(saved);
                }
            }

            diagnosticPhase = "handle_table_status";
            handleTableStatus(saved);

            diagnosticPhase = "hydrate_saved_order";
            Order hydrated = hydrateOrder(saved);

            diagnosticPhase = "enqueue_cloud_print_jobs";
            enqueueCloudPrintJobs(hydrated);
            logCreditOrderCreateSuccess(logCreditDiagnostics, hydrated);
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

    @Transactional
    public Order updateOrder(UUID id, Order updates) {
        Order oldOrder = getOrder(id);
        
        // 1. Create a deep copy of the old order as a VOID record
        String originalOrderNo = oldOrder.getOrderNo();
        oldOrder.setOrderNo(originalOrderNo + "_VOID_" + (oldOrder.getRevisionNumber() != null ? oldOrder.getRevisionNumber() : 0));
        oldOrder.setOrderStatus("VOID");
        oldOrder.setIsactive("N");
        orderRepository.saveAndFlush(oldOrder);
        
        // 2. VOID the linked invoice
        List<UUID> oldInvoiceIdList = new java.util.ArrayList<>();
        invoiceRepository.findByOrderId(id).forEach(inv -> {
            oldInvoiceIdList.add(inv.getId());
            accountingPostingService.reverseInvoice(inv, "Order revised");
            inv.setInvoiceNo(inv.getInvoiceNo() + "_VOID_" + (oldOrder.getRevisionNumber() != null ? oldOrder.getRevisionNumber() : 0));
            inv.setStatus("VOID");
            invoiceRepository.saveAndFlush(inv);
        });

        paymentRepository.findByOrderId(id).forEach(payment -> {
            accountingPostingService.reversePayment(payment, "Order revised");
            payment.setReferenceNo((payment.getReferenceNo() != null ? payment.getReferenceNo() : "PAYMENT")
                    + "_VOID_" + (oldOrder.getRevisionNumber() != null ? oldOrder.getRevisionNumber() : 0));
            payment.setDocStatus("VOID");
            payment.setIsactive("N");
            paymentRepository.saveAndFlush(payment);
        });

        // 3. Create the correct entity subtype to preserve the JPA discriminator value.
        //    Using Order.builder().build() always creates the base Order class (@DiscriminatorValue("SALE")),
        //    which would corrupt Purchase/Expense orders in the database.
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
        newOrder.setOrderStatus(updates.getOrderStatus() != null ? updates.getOrderStatus() : oldOrder.getOrderStatus());
        newOrder.setPaymentStatus(updates.getPaymentStatus() != null ? updates.getPaymentStatus() : oldOrder.getPaymentStatus());

        // Parties & references (merge from updates, fallback to old)
        newOrder.setVendorId(updates.getVendorId() != null ? updates.getVendorId() : oldOrder.getVendorId());
        newOrder.setCustomerId(updates.getCustomerId() != null ? updates.getCustomerId() : oldOrder.getCustomerId());
        newOrder.setIsCredit(updates.getIsCredit() != null ? updates.getIsCredit() : oldOrder.getIsCredit());
        newOrder.setCreditCustomerId(updates.getCreditCustomerId() != null ? updates.getCreditCustomerId() : oldOrder.getCreditCustomerId());
        newOrder.setCustomerName(updates.getCustomerName() != null ? updates.getCustomerName() : oldOrder.getCustomerName());
        newOrder.setCustomerPhone(updates.getCustomerPhone() != null ? updates.getCustomerPhone() : oldOrder.getCustomerPhone());
        newOrder.setCustomerIds(updates.getCustomerIds() != null ? updates.getCustomerIds() : oldOrder.getCustomerIds());
        newOrder.setWarehouseId(updates.getWarehouseId() != null ? updates.getWarehouseId() : oldOrder.getWarehouseId());
        newOrder.setPricelistId(updates.getPricelistId() != null ? updates.getPricelistId() : oldOrder.getPricelistId());
        newOrder.setCurrencyId(updates.getCurrencyId() != null ? updates.getCurrencyId() : oldOrder.getCurrencyId());
        newOrder.setPaymentMethod(updates.getPaymentMethod() != null ? updates.getPaymentMethod() : oldOrder.getPaymentMethod());
        newOrder.setReference(updates.getReference() != null ? updates.getReference() : oldOrder.getReference());

        // Dates & addresses
        newOrder.setOrderDate(updates.getOrderDate() != null ? updates.getOrderDate() : oldOrder.getOrderDate());
        newOrder.setFulfillmentType(updates.getFulfillmentType() != null ? updates.getFulfillmentType() : oldOrder.getFulfillmentType());
        newOrder.setTableNumber(updates.getTableNumber());
        newOrder.setTableId(updates.getTableId());

        // Financial totals
        newOrder.setTotalAmount(updates.getTotalAmount() != null ? updates.getTotalAmount() : oldOrder.getTotalAmount());
        newOrder.setTotalTaxAmount(updates.getTotalTaxAmount() != null ? updates.getTotalTaxAmount() : oldOrder.getTotalTaxAmount());
        newOrder.setTotalDiscountAmount(updates.getTotalDiscountAmount() != null ? updates.getTotalDiscountAmount() : oldOrder.getTotalDiscountAmount());
        newOrder.setGrandTotal(updates.getGrandTotal() != null ? updates.getGrandTotal() : oldOrder.getGrandTotal());
        newOrder.setDescription(updates.getDescription() != null ? updates.getDescription() : oldOrder.getDescription());

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
                copy.setIsPackagedGood(oldLine.getIsPackagedGood());
                copy.setQuantity(oldLine.getQuantity());
                copy.setUnitOfMeasure(oldLine.getUnitOfMeasure());
                copy.setUnitPrice(oldLine.getUnitPrice());
                copy.setTaxRate(oldLine.getTaxRate());
                copy.setTaxAmount(oldLine.getTaxAmount());
                copy.setDiscountAmount(oldLine.getDiscountAmount());
                copy.setLineTotal(oldLine.getLineTotal());
                newOrder.addLine(copy);
            }
        }
        hydrateOrderLines(newOrder);
        prepareCreditCustomer(newOrder, Boolean.TRUE.equals(newOrder.getIsCredit()));
        prepareCustomerFields(newOrder);
        
        Order saved = orderRepository.save(newOrder);
        linkCustomersToSavedOrder(saved);
        
        // 4. Generate new ERP documents (Invoice/Payment)
        UUID oldInvId = oldInvoiceIdList.isEmpty() ? null : oldInvoiceIdList.get(0);
        if (shouldGenerateInvoice(saved)) {
            generateInvoice(saved, oldInvId);
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
                generatePayment(saved, paymentMethod);
            }
            if ("COMPLETED".equalsIgnoreCase(saved.getOrderStatus())) {
                processInventoryForOrder(saved);
            }
        } else {
            // Sales
            if ("PAID".equalsIgnoreCase(saved.getPaymentStatus())) {
                String salePaymentMethod = saved.getReference() != null ? saved.getReference() : "CASH";
                generatePayment(saved, salePaymentMethod);
                accountingPostingService.postSaleCogs(saved);
            } else if (isCompletedCreditSale(saved)) {
                accountingPostingService.postSaleCogs(saved);
            }
        }
        
        handleTableStatus(saved);

        // Inventory Hook: If PURCHASE order is COMPLETED, update stock
        if (saved.getOrderType() == OrderType.PURCHASE && "COMPLETED".equalsIgnoreCase(saved.getOrderStatus())) {
            processInventoryForOrder(saved);
        }
        
        Order hydrated = hydrateOrder(saved);
        enqueueCloudPrintJobs(hydrated);
        return hydrated;
    }

    @Transactional
    @org.springframework.retry.annotation.Retryable(
        value = { org.springframework.orm.ObjectOptimisticLockingFailureException.class },
        maxAttempts = 3,
        backoff = @org.springframework.retry.annotation.Backoff(delay = 50)
    )
    public Order updateOrderStatus(UUID id, String status, String paymentStatus, String description) {
        Order order = getOrder(id);
        if (status != null) order.setOrderStatus(status);
        if (paymentStatus != null) order.setPaymentStatus(paymentStatus);
        if (description != null) order.setDescription(description);
        prepareCreditCustomer(order, Boolean.TRUE.equals(order.getIsCredit()));
        
        Order result = orderRepository.save(order);
        
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
            if ("COMPLETED".equalsIgnoreCase(result.getOrderStatus()) && "PAID".equalsIgnoreCase(result.getPaymentStatus())) {
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
        return hydrated;
    }

    @Transactional
    public Order updateOrderStatus(UUID id, String status) {
        return updateOrderStatus(id, status, null, null);
    }

    @Transactional
    @org.springframework.retry.annotation.Retryable(
        value = { org.springframework.orm.ObjectOptimisticLockingFailureException.class },
        maxAttempts = 3,
        backoff = @org.springframework.retry.annotation.Backoff(delay = 50)
    )
    public Order updateOrderStatus(UUID id, OrderStatus status, PaymentStatus paymentStatus, String description) {
        return updateOrderStatus(
            id,
            status != null ? status.name() : null,
            paymentStatus != null ? paymentStatus.name() : null,
            description
        );
    }

    @Transactional
    @org.springframework.retry.annotation.Retryable(
        value = { org.springframework.orm.ObjectOptimisticLockingFailureException.class },
        maxAttempts = 3,
        backoff = @org.springframework.retry.annotation.Backoff(delay = 50)
    )
    public Order updateOrderStatus(UUID id, OrderStatus status) {
        return updateOrderStatus(id, status, null, null);
    }

    @Transactional
    public Order billOrder(UUID id) {
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
        return hydrateOrder(saved);
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
        BigDecimal currentTotal = moneyValue(order.getGrandTotal());

        if (discountAmount.compareTo(BigDecimal.ZERO) > 0) {
            order.setTotalDiscountAmount(moneyValue(order.getTotalDiscountAmount()).add(discountAmount));
        }

        BigDecimal payable = currentTotal.subtract(discountAmount).add(roundOffAmount);
        if (payable.compareTo(BigDecimal.ZERO) < 0) {
            payable = BigDecimal.ZERO;
        }
        payable = payable.setScale(2, RoundingMode.HALF_UP);

        if (discountAmount.compareTo(BigDecimal.ZERO) > 0 || roundOffAmount.compareTo(BigDecimal.ZERO) != 0) {
            order.setGrandTotal(payable);
        }

        order.setReference(paymentMethod);
        order.setOrderStatus("COMPLETED");
        order.setPaymentStatus("PAID");

        String settlementDescription = buildSettlementDescription(safeRequest, paymentMethod);
        if (!settlementDescription.isBlank()) {
            order.setDescription(appendDescription(order.getDescription(), settlementDescription));
        }

        Order saved = orderRepository.save(order);

        // Fix: update existing invoice if discount/roundoff changed the total
        if (discountAmount.compareTo(BigDecimal.ZERO) > 0 || roundOffAmount.compareTo(BigDecimal.ZERO) != 0) {
            List<Invoice> existingInvoices = invoiceRepository.findByOrderId(saved.getId());
            for (Invoice existingInv : existingInvoices) {
                if (!"VOID".equalsIgnoreCase(existingInv.getStatus())) {
                    existingInv.setTotalAmount(saved.getGrandTotal());
                    existingInv.setAmountDue(saved.getGrandTotal());
                    invoiceRepository.save(existingInv);
                    accountingPostingService.replaceInvoiceJournal(saved, existingInv, "Invoice amount corrected after discount/roundoff");
                }
            }
        }
        generateInvoice(saved);

        BigDecimal amountPaid = safeRequest.getAmountPaid() != null
                ? moneyValue(safeRequest.getAmountPaid())
                : payable;
        generatePayment(saved, paymentMethod, null, amountPaid, settlementDescription,
                safeRequest.getCashAmount(), safeRequest.getOnlineAmount(), safeRequest.getPaymentSplits());
        accountingPostingService.postSaleCogs(saved);

        handleTableStatus(saved);

        if (saved.getOrderType() == OrderType.PURCHASE && "COMPLETED".equalsIgnoreCase(saved.getOrderStatus())) {
            processInventoryForOrder(saved);
        }

        return hydrateOrder(saved);
    }

    @Transactional
    public Order completeCreditOrder(UUID id, OrderCreditCompletionRequest request) {
        Order order = getOrder(id);
        ensureOrderCanChange(order, "settle");
        if ("PAID".equalsIgnoreCase(order.getPaymentStatus())) {
            throw new BusinessException("Paid orders cannot be converted to credit");
        }

        OrderCreditCompletionRequest safeRequest = request == null ? new OrderCreditCompletionRequest() : request;
        order.setCreditCustomerId(safeRequest.getCreditCustomerId() != null ? safeRequest.getCreditCustomerId() : order.getCreditCustomerId());
        prepareCreditCustomer(order, true);
        prepareCustomerFields(order);

        BigDecimal discountAmount = moneyValue(safeRequest.getDiscountAmount());
        BigDecimal roundOffAmount = moneyValue(safeRequest.getRoundOffAmount());
        BigDecimal currentTotal = moneyValue(order.getGrandTotal());
        if (discountAmount.compareTo(BigDecimal.ZERO) > 0) {
            order.setTotalDiscountAmount(moneyValue(order.getTotalDiscountAmount()).add(discountAmount));
        }
        BigDecimal payable = currentTotal.subtract(discountAmount).add(roundOffAmount);
        if (payable.compareTo(BigDecimal.ZERO) < 0) {
            payable = BigDecimal.ZERO;
        }
        payable = payable.setScale(2, RoundingMode.HALF_UP);
        if (discountAmount.compareTo(BigDecimal.ZERO) > 0 || roundOffAmount.compareTo(BigDecimal.ZERO) != 0) {
            order.setGrandTotal(payable);
        }

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
        if (!invoiceRepository.findByOrderId(order.getId()).isEmpty()) return null;

        UUID clientId = order.getClientId();
        UUID orgId = order.getOrgId();
        
        // Determine invoice document type from order type
        OrderType orderType = order.getOrderType() == null ? OrderType.SALE : order.getOrderType();
        DocumentType invoiceDocType = switch (orderType) {
            case PURCHASE -> DocumentType.VENDOR_BILL;
            case EXPENSE  -> DocumentType.EXPENSE_RECEIPT;
            default       -> DocumentType.CUSTOMER_INVOICE;
        };
        
        String invNo = resolveDocumentNumber(order, invoiceDocType, requestedInvoiceNo);
        
        // Map to entity InvoiceType
        InvoiceType invoiceType = switch (invoiceDocType) {
            case VENDOR_BILL -> InvoiceType.VENDOR_BILL;
            case EXPENSE_RECEIPT -> InvoiceType.EXPENSE_RECEIPT;
            default -> InvoiceType.CUSTOMER_INVOICE;
        };

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
            .invoiceDate(sourceBusinessDateTime(order))
            .totalAmount(order.getGrandTotal())
            .amountDue(order.getGrandTotal())
            .status("UNPAID")
            .isPaid(false)
            .isCredit(Boolean.TRUE.equals(order.getIsCredit()))
            .originalInvoiceId(originalInvoiceId)
            .build();
            
        invoice.setClientId(clientId);
        invoice.setOrgId(orgId);

        if (order.getLines() != null) {
            for (OrderLine ol : order.getLines()) {
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
                    .createdBy(ol.getCreatedBy())
                    .updatedBy(ol.getUpdatedBy())
                    .build();
                invoice.addLine(il);
            }
        }

        Invoice savedInvoice = invoiceRepository.save(invoice);
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
    public void generatePayment(Order order, String paymentMethod, String requestedPaymentNo, BigDecimal amountPaid, String description) {
        generatePayment(order, paymentMethod, requestedPaymentNo, amountPaid, description, null, null);
    }

    @Transactional
    public void generatePayment(Order order, String paymentMethod, String requestedPaymentNo, BigDecimal amountPaid, String description,
                                BigDecimal cashAmount, BigDecimal onlineAmount) {
        generatePayment(order, paymentMethod, requestedPaymentNo, amountPaid, description, cashAmount, onlineAmount, null);
    }

    @Transactional
    public void generatePayment(Order order, String paymentMethod, String requestedPaymentNo, BigDecimal amountPaid, String description,
                                BigDecimal cashAmount, BigDecimal onlineAmount,
                                List<OrderSettleRequest.PaymentSplitRequest> paymentSplits) {
        if (order.getPaymentNo() != null && !order.getPaymentNo().isEmpty()) return;
        if (!paymentRepository.findByOrderId(order.getId()).isEmpty()) return;

        // Try to find the invoice
        List<Invoice> invoices = invoiceRepository.findByOrderId(order.getId());
        Invoice invoice = invoices.isEmpty() ? generateInvoice(order) : invoices.get(0);
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
            .build();
            
        payment.setClientId(clientId);
        payment.setOrgId(orgId);

        Payment savedPayment = paymentRepository.save(payment);
        savePaymentSplits(savedPayment, storedPaymentMethod, amountPaid, cashAmount, onlineAmount, paymentSplits);
        
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

    private void savePaymentSplits(Payment payment, String paymentMethod, BigDecimal amountPaid, BigDecimal cashAmount, BigDecimal onlineAmount,
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
                .referenceNo(referenceNo == null || referenceNo.isBlank() ? payment.getReferenceNo() : referenceNo.trim())
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
        if (order.getTableId() == null) return;

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
        String method = paymentMethod == null || paymentMethod.isBlank() ? "CASH" : paymentMethod.trim().toUpperCase();
        if (!PAYMENT_METHODS.contains(method)) {
            return "CASH";
        }
        return method;
    }

    private String normalizePaymentSplitMethod(String paymentMethod) {
        String method = paymentMethod == null || paymentMethod.isBlank() ? "" : paymentMethod.trim().toUpperCase();
        if (!PAYMENT_SPLIT_METHODS.contains(method)) {
            throw new BusinessException("Unsupported payment split method: " + String.valueOf(paymentMethod));
        }
        return method;
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

    private String buildSettlementDescription(OrderSettleRequest request, String paymentMethod) {
        List<String> parts = new java.util.ArrayList<>();
        if ("MIXED".equalsIgnoreCase(paymentMethod)) {
            if (hasExplicitPaymentSplits(request)) {
                List<String> splitParts = request.getPaymentSplits().stream()
                        .filter(Objects::nonNull)
                        .map(split -> normalizePaymentSplitMethod(split.getPaymentMethod()) + ": " + moneyValue(split.getAmount()))
                        .collect(Collectors.toList());
                parts.add(String.join(", ", splitParts));
            } else {
                parts.add("Cash: " + moneyValue(request.getCashAmount()));
                parts.add("Online: " + moneyValue(request.getOnlineAmount()));
            }
        }
        if (request.getDiscountAmount() != null && moneyValue(request.getDiscountAmount()).compareTo(BigDecimal.ZERO) > 0) {
            parts.add("Discount: " + moneyValue(request.getDiscountAmount()));
        }
        if (request.getRoundOffAmount() != null && moneyValue(request.getRoundOffAmount()).compareTo(BigDecimal.ZERO) != 0) {
            parts.add("Round off: " + moneyValue(request.getRoundOffAmount()));
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
                    order.getOrgId()
                );
            }
        }
    }
}
